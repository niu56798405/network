package org.gcoder.network.kcp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import org.gcoder.network.kcp.base.Kcp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class KcpSession {
	
	private static final Logger LOG = LoggerFactory.getLogger(KcpSession.class);
	
	public static final long TTL_DEFAULT_IN_MILLIS = 3 * 60 * 1000;

	private final KcpSessionManager sessionManager;
	private final KcpSession session;
	
	private final Kcp kcp;
	private final KcpLoopGroup group;
	private final KcpLoopThread loopThread;
	private final Executor executor;
	
	private final AtomicBoolean open = new AtomicBoolean(false);
	
	private long lastPingTime = System.currentTimeMillis();
	
	private ConcurrentLinkedQueue<ByteBuf> recevieQueue = new ConcurrentLinkedQueue<>();
	
	public KcpSession(Kcp kcp, KcpSessionManager sessionManager) {
		this.sessionManager = sessionManager;
		this.kcp = kcp;
		this.group = sessionManager.getKcpLoopGroup();
		this.loopThread = group.register(this);
		this.open.compareAndSet(false, true);
		this.executor = sessionManager.getExecutor();
		this.session = this;
		if (LOG.isDebugEnabled()) {
			LOG.debug("Kcp Session Create : conv={}", getConv());
		}
	}

	public void inputReliable(ByteBuf udpData) {
		recevieQueue.add(udpData);
	}
	
	public abstract void recevieProtocol(ByteBuf data);
	
	public void outputReliable(ByteBuf data) {
		kcp.send(data);
	}
	
	public void close() {
		sessionManager.remove(getConv());
		open.compareAndSet(true, false);
		group.deregister(this);
		kcp.release();
	}
	
	/**
	 * 循环调用
	 */
	protected final void onTick() {
		
		if (!open.get()) {
			return;
		}
		
		boolean recevied = false;
		long currentTimeMillis = System.currentTimeMillis();

		while(true){
			ByteBuf poll = recevieQueue.poll();
			if (poll == null) {
				break;
			} else if(!recevied) {
				recevied = true;
			}
			try {
				kcp.input(poll);
			} catch (Exception e) {
				if (LOG.isErrorEnabled()) {
					LOG.error("kcp input error", e);
				}
			} finally {
				ReferenceCountUtil.release(poll);
			}
		}
		
		kcp.update((int) currentTimeMillis);
		
		while (true) {
			Optional<ByteBuf> recv = kcp.recv();
			if (recv.isPresent()) {
				recevieProtocol(recv.get());
			} else {
				break;
			}
		}
		
		// ttl
		if (recevied) {
			lastPingTime = currentTimeMillis;
		} else if (currentTimeMillis - lastPingTime > TTL_DEFAULT_IN_MILLIS) {
			close();
			if (LOG.isInfoEnabled()) {
				LOG.info("CLOSE : Kcp Session TTL : conv={}", getConv());
			}
			return;
		}
		
		if (kcp.waitSnd() > 128){
			session.close();
			if (LOG.isErrorEnabled()) {
				LOG.error("CLOSE : Kcp Session So Many Message Wait To Send : conv={}", getConv());
			}
			return;
		}
	}
	
	public int getConv() {
		return kcp.getConv();
	}
	
	public KcpLoopGroup getGroup() {
		return group;
	}
	
	public KcpLoopThread getLoopThread() {
		return loopThread;
	}
	
	public ByteBufAllocator getAllocator() {
		return kcp.getAllocator();
	}

	public Executor getExecutor() {
		return executor;
	}

	public KcpSessionManager getSessionManager() {
		return sessionManager;
	}
	
}

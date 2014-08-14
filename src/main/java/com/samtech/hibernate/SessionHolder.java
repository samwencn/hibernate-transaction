package com.samtech.hibernate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.Transaction;



public class SessionHolder {

	private static final Object DEFAULT_KEY = new Object();
	//private static final ThreadLocal<SessionHolder> threadLocal = new ThreadLocal<SessionHolder>();
	/**
	 * This Map needs to be concurrent because there might be multi-threaded
	 * access in the case of JTA with remote transaction propagation.
	 */
	private final Map<Object, Session> sessionMap = new ConcurrentHashMap<Object, Session>(1);

	private Transaction transaction;

	private FlushMode previousFlushMode;


	public SessionHolder(Session session) {
		addSession(session);
	}

	public SessionHolder(Object key, Session session) {
		addSession(key, session);
	}


	public Session getSession() {
		return getSession(DEFAULT_KEY);
	}

	public Session getSession(Object key) {
		return this.sessionMap.get(key);
	}

	public Session getValidatedSession() {
		return getValidatedSession(DEFAULT_KEY);
	}

	public Session getValidatedSession(Object key) {
		Session session = this.sessionMap.get(key);
		// Check for dangling Session that's around but already closed.
		// Effectively an assertion: that should never happen in practice.
		// We'll seamlessly remove the Session here, to not let it cause
		// any side effects.
		if (session != null && !session.isOpen()) {
			this.sessionMap.remove(key);
			session = null;
		}
		return session;
	}

	public Session getAnySession() {
		if (!this.sessionMap.isEmpty()) {
			return this.sessionMap.values().iterator().next();
		}
		return null;
	}

	public void addSession(Session session) {
		addSession(DEFAULT_KEY, session);
	}

	public void addSession(Object key, Session session) {
		/*Assert.notNull(key, "Key must not be null");
		Assert.notNull(session, "Session must not be null");*/
		this.sessionMap.put(key, session);
	}

	public Session removeSession(Object key) {
		return this.sessionMap.remove(key);
	}

	public boolean containsSession(Session session) {
		return this.sessionMap.containsValue(session);
	}

	public boolean isEmpty() {
		return this.sessionMap.isEmpty();
	}

	public boolean doesNotHoldNonDefaultSession() {
		return this.sessionMap.isEmpty() ||
				(this.sessionMap.size() == 1 && this.sessionMap.containsKey(DEFAULT_KEY));
	}


	public void setTransaction(Transaction transaction) {
		this.transaction = transaction;
	}

	public Transaction getTransaction() {
		return this.transaction;
	}

	public void setPreviousFlushMode(FlushMode previousFlushMode) {
		this.previousFlushMode = previousFlushMode;
	}

	public FlushMode getPreviousFlushMode() {
		return this.previousFlushMode;
	}


	
	public void clear() {
		//super.clear();
		this.transaction = null;
		this.previousFlushMode = null;
	}

}

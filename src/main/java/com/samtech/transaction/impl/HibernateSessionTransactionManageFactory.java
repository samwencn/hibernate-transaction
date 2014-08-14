package com.samtech.transaction.impl;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.concurrent.ConcurrentHashMap;

import javassist.util.proxy.MethodFilter;
import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.ProxyFactory;
import javassist.util.proxy.ProxyObject;

import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.service.jdbc.connections.spi.ConnectionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.samtech.service.ISession;
import com.samtech.transaction.TransactionManageFactory;
import com.samtech.transaction.Transactional;
/**
 * 
 *  session just inject(com.samtech.service.ISession#setSession) before call
 *
 */
public class HibernateSessionTransactionManageFactory implements
		TransactionManageFactory {
	private static Logger logger=LoggerFactory.getLogger(TransactionManageFactory.class);
	SessionFactory hsf=null;
	
	private ConcurrentHashMap<String, Class<?>> classes=new ConcurrentHashMap<String, Class<?>>(30);
			
	public HibernateSessionTransactionManageFactory(SessionFactory sf) {
		this.hsf=sf;
	}
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public <T> T createTransactionObject(Class<T> clazz) {
		Class proxyClass = null;
		if (!classes.containsKey(clazz.getName())) {
			ProxyFactory proxyFactory = new ProxyFactory();
			proxyFactory.setSuperclass(clazz);
			Class<?>[] interfaces = clazz.getInterfaces();
			proxyFactory.setInterfaces(interfaces);
			MethodFilter filter = new MethodFilter() {

				@Override
				public boolean isHandled(Method m) {
					Method[] methods = ISession.class.getMethods();
					for (int i = 0; i < methods.length; i++) {
						Method method = methods[i];
						if (m.getName().equals(method.getName()))
							return false;

					}
					return true;
				}

			};
			proxyFactory.setFilter(filter);
			proxyClass = proxyFactory.createClass();
			classes.put(clazz.getName(), proxyClass);
		} else {
			proxyClass = classes.get(clazz.getName());
		}
		try {
			
			T newObj = (T) proxyClass.newInstance();

			MethodHandler mi = new HibernateTransactionMethodHandler(hsf);
			((ProxyObject) newObj).setHandler(mi);
			return newObj;
		} catch (InstantiationException e) {
			logger.error("", e);
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			logger.error("", e);
			throw new RuntimeException(e);
		}
	}
	@SuppressWarnings("unchecked")
	@Override
	public <T> T createTransactionObject(Class<T> clazz,
			 String[] ignoreMethodNames) {
		//Class<? extends Object> clazz = obj.getClass();
		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.setSuperclass(clazz);
		Class<?>[] interfaces = clazz.getInterfaces();
		proxyFactory.setInterfaces(interfaces);
		int len=0;
		if(ignoreMethodNames!=null && ignoreMethodNames.length>0)
			len=ignoreMethodNames.length;
		final String[] ignoreMNames=new String[len];
		if(ignoreMethodNames!=null && ignoreMethodNames.length>0)
			System.arraycopy(ignoreMethodNames, 0, ignoreMNames, 0, ignoreMethodNames.length);
		MethodFilter filter=new MyMethodFilter(ignoreMNames);
		proxyFactory.setFilter(filter);
		Class<?> proxyClass = proxyFactory.createClass();
		try {
			T newObj = (T) proxyClass.newInstance();
			MethodHandler mi=new HibernateTransactionMethodHandler(hsf);
			((ProxyObject)newObj).setHandler(mi);
			return newObj;
		} catch (InstantiationException e) {
			logger.error("", e);
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			logger.error("", e);
			throw new RuntimeException(e);
		}
	}
	
	private class MyMethodFilter implements MethodFilter{
		String[] ignoreMethods=null;
		public MyMethodFilter(String[] ignoreMethods){
			this.ignoreMethods=ignoreMethods;
		}
		@Override
		public boolean isHandled(Method m) {
			Method[] methods = ISession.class.getMethods();
			for (int i = 0; i < methods.length; i++) {
				Method method = methods[i];
				if(m.getName().equals(method.getName()))
					return false;
				
			}
			if(ignoreMethods!=null && ignoreMethods.length>0){
				for (int i = 0; i < ignoreMethods.length; i++) {
					String methodName = ignoreMethods[i];
					if(m.getName().equals(methodName))
						return false;
					
				}	
			}
			return true;
		}
		
	}
	private  class HibernateTransactionMethodHandler implements MethodHandler{
		//T thiz;
		//SessionHolder sessionHolder;
		private SessionFactory sf;
		HibernateTransactionMethodHandler(SessionFactory hsf){
			
			sf=hsf;
		}
		@Override
		public Object invoke(Object self, Method thisMethod, Method proceed,
				Object[] args) throws Throwable {
			Transactional ann = thisMethod.getAnnotation(Transactional.class);
			Transaction transaction=null;
			FlushMode pv =null;
			Session session =null;
			Connection connection =null;
			ConnectionProvider connectionProvider=null;
			synchronized(sf){
					session=sf.openSession();
					if(!session.isConnected()){
						
					}
			}
			//SessionHolder sessionHolder= new SessionHolder(session);
			if(ann!=null){
				
				boolean readOnly = ann.readOnly();
				pv = session.getFlushMode();
				//sessionHolder.setPreviousFlushMode(pv);
				if(readOnly)
					session.setFlushMode(FlushMode.MANUAL);
				int timeout = ann.timeout();
				if(timeout>0){
					transaction = session.getTransaction();
					transaction.setTimeout(timeout);
					transaction.begin();
				}else{
					transaction = session.beginTransaction();
				}
			}else {
				//session = sessionHolder.getSession();
				pv = session.getFlushMode();
				//sessionHolder.setPreviousFlushMode(pv);
				session.setFlushMode(FlushMode.AUTO);
			}
			//if(transaction!=null)sessionHolder.setTransaction(transaction);
			try{
				if(self instanceof ISession){
					ISession service=(ISession)self;
					service.setSession(session);
				}
				Object obj = proceed.invoke(self, args);
				if(transaction!=null){
					transaction.commit();
				}
				return obj;
			}catch(Throwable th){
				if(transaction!=null){
					transaction.rollback();
				}
				logger.error("", th);
				throw th;
			}finally{
				//session = sessionHolder.getSession();
				if(session!=null){
					//FlushMode pv = sessionHolder.getPreviousFlushMode();
					try{
						if(session.isOpen()){
							if(pv!=null)
								session.setFlushMode(pv);
							session.clear();
							session.close();
						}
					}catch(Exception ex){
						
					}catch(Throwable t){
						
					}
					if(connection!=null && connectionProvider!=null){
						connectionProvider.closeConnection(connection);
					}
					
				}
			}
			
			
		}
		
	}

	

}

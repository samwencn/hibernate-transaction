package com.samtech.test;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URLEncoder;

import javassist.util.proxy.MethodFilter;
import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.ProxyObject;

import javassist.util.proxy.ProxyFactory;

public class Main {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		/*MyBizTest t1 = createTransactionObject(new  MyBizTest());
		t1.setSession(null);
		t1.noInterfaceMethod();
		Long sub = t1.sub(new Long(18), new Integer(3));
		System.out.println("sub:"+sub);*/
		//https://open.weixin.qq.com/connect/oauth2/authorize?appid=wx266930e7748a185f&redirect_uri=http%3A%2F%2Fphoto.mygcs.cc%2Fweixin%2Fsns%2Fdemo%2Foauth.xhtml&response_type=code&scope=snsapi_userinfo&state=1
		String s="http://photo.mygcs.cc/weixin/sns/demo/oauth.xhtml";
		String encode;
		try {
			encode = URLEncoder.encode(s, "utf8");
			System.out.println(encode);
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public static <T> T createTransactionObject(final T obj) {
		Class<? extends Object> clazz = obj.getClass();
		ProxyFactory proxyFactory = new ProxyFactory();
		proxyFactory.setSuperclass(clazz);
		Class<?>[] interfaces = clazz.getInterfaces();
		proxyFactory.setInterfaces(interfaces);
		MethodFilter filter=new MethodFilter(){

			@Override
			public boolean isHandled(Method m) {
				if(m.getName().equals("setSession"))
					return false;
				return true;
			}
			
		};
		proxyFactory.setFilter(filter);
		Class proxyClass = proxyFactory.createClass();
		try {
			T newObj = (T) proxyClass.newInstance();
			
			
			MethodHandler mi=new MethodHandler(){

				@Override
				public Object invoke(Object self, Method thisMethod,
						Method proceed, Object[] args) throws Throwable {
					String clzname = self.getClass().getName();
					System.out.println(clzname);
					return proceed.invoke(self, args);
					
				}};
			((ProxyObject)newObj).setHandler(mi);
			return newObj;
		} catch (InstantiationException e) {
			//logger.error("", e);
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			//logger.error("", e);
			throw new RuntimeException(e);
		}
	}

}

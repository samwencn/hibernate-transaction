package com.samtech.test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javassist.CannotCompileException;
import javassist.bytecode.*;
import javassist.util.proxy.MethodFilter;
import javassist.util.proxy.MethodHandler;

import javassist.util.proxy.ProxyObject;
import javassist.util.proxy.RuntimeSupport;


public class ProxyCreator {

	private static final Class OBJECT_TYPE = Object.class;
	private static final String DEFAULT_TARGET_NAME = "targetObject";
	private static final String DEFAULT_TARGET_SETTER = "setTargetObject";
	private Class superClass;
	private Class[] interfaces;
	private MethodFilter methodFilter;
	private MethodHandler handler; // retained for legacy usage
	private List signatureMethods;
	private boolean hasGetHandler;
	private byte[] signature;
	private String classname;
	private String basename;
	private String superName;
	private Class thisClass;

	/**
	 * Sets the super class of a proxy class.
	 */
	public void setSuperclass(Class clazz) {
		superClass = clazz;
		// force recompute of signature
		signature = null;
	}

	/**
	 * Obtains the super class set by <code>setSuperclass()</code>.
	 * 
	 * @since 3.4
	 */
	public Class getSuperclass() {
		return superClass;
	}

	/**
	 * Sets the interfaces of a proxy class.
	 */
	public void setInterfaces(Class[] ifs) {
		interfaces = ifs;
		// force recompute of signature
		signature = null;
	}

	/**
	 * Obtains the interfaces set by <code>setInterfaces</code>.
	 * 
	 * @since 3.4
	 */
	public Class[] getInterfaces() {
		return interfaces;
	}

	/*
	 * getMethods() may set hasGetHandler to true.
	 */
	private HashMap getMethods(Class superClass, Class[] interfaceTypes) {
		HashMap hash = new HashMap();
		HashSet set = new HashSet();
		for (int i = 0; i < interfaceTypes.length; i++)
			getMethods(hash, interfaceTypes[i], set);

		getMethods(hash, superClass, set);
		return hash;
	}

	private void getMethods(HashMap hash, Class clazz, Set visitedClasses) {
		// This both speeds up scanning by avoiding duplicate interfaces and is
		// needed to
		// ensure that superinterfaces are always scanned before subinterfaces.
		if (!visitedClasses.add(clazz))
			return;

		Class[] ifs = clazz.getInterfaces();
		for (int i = 0; i < ifs.length; i++)
			getMethods(hash, ifs[i], visitedClasses);

		Class parent = clazz.getSuperclass();
		if (parent != null)
			getMethods(hash, parent, visitedClasses);

		/*
		 * Java 5 or later allows covariant return types. It also allows
		 * contra-variant parameter types if a super class is a generics with
		 * concrete type arguments such as Foo<String>. So the method-overriding
		 * rule is complex.
		 */
		Method[] methods = getDeclaredMethods(clazz);
		for (int i = 0; i < methods.length; i++)
			if (!Modifier.isPrivate(methods[i].getModifiers())) {
				Method m = methods[i];
				String key = m.getName() + ':'
						+ RuntimeSupport.makeDescriptor(m); // see keyToDesc().
				/*
				 * if (key.startsWith(HANDLER_GETTER_KEY)) hasGetHandler = true;
				 */

				// JIRA JASSIST-85
				// put the method to the cache, retrieve previous definition (if
				// any)
				Method oldMethod = (Method) hash.put(key, methods[i]);

				// check if visibility has been reduced
				if (null != oldMethod
						&& Modifier.isPublic(oldMethod.getModifiers())
						&& !Modifier.isPublic(methods[i].getModifiers())) {
					// we tried to overwrite a public definition with a
					// non-public definition,
					// use the old definition instead.
					hash.put(key, oldMethod);
				}
			}
	}

	private static Comparator sorter = new Comparator() {

		public int compare(Object o1, Object o2) {
			Map.Entry e1 = (Map.Entry) o1;
			Map.Entry e2 = (Map.Entry) o2;
			String key1 = (String) e1.getKey();
			String key2 = (String) e2.getKey();
			return key1.compareTo(key2);
		}
	};

	private void computeSignature(MethodFilter filter) // throws
														// CannotCompileException
	{
		makeSortedMethodList();

		int l = signatureMethods.size();
		int maxBytes = ((l + 7) >> 3);
		signature = new byte[maxBytes];
		for (int idx = 0; idx < l; idx++) {
			Map.Entry e = (Map.Entry) signatureMethods.get(idx);
			Method m = (Method) e.getValue();
			int mod = m.getModifiers();
			if (!Modifier.isFinal(mod) && !Modifier.isStatic(mod)
					&& isVisible(mod, basename, m)
					&& (filter == null || filter.isHandled(m))) {
				setBit(signature, idx);
			}
		}
	}

	private void setBit(byte[] signature, int idx) {
		int byteIdx = idx >> 3;
		if (byteIdx < signature.length) {
			int bitIdx = idx & 0x7;
			int mask = 0x1 << bitIdx;
			int sigByte = signature[byteIdx];
			signature[byteIdx] = (byte) (sigByte | mask);
		}
	}

	private void makeSortedMethodList() {
		checkClassAndSuperName();

		hasGetHandler = false; // getMethods() may set this to true.
		HashMap allMethods = getMethods(superClass, interfaces);
		signatureMethods = new ArrayList(allMethods.entrySet());
		Collections.sort(signatureMethods, sorter);
	}

	private ClassFile make() throws CannotCompileException {
		ClassFile cf = new ClassFile(false, classname, superName);
		cf.setAccessFlags(AccessFlag.PUBLIC);
		setInterfaces(cf, interfaces);
		ConstPool pool = cf.getConstPool();

		// legacy: we only add the static field for the default interceptor if
		// caching is disabled
		/*
		 * if (!factoryUseCache) { FieldInfo finfo = new FieldInfo(pool,
		 * DEFAULT_INTERCEPTOR, HANDLER_TYPE);
		 * finfo.setAccessFlags(AccessFlag.PUBLIC | AccessFlag.STATIC);
		 * cf.addField(finfo); }
		 */
		String handleType = 'L' + superClass.getName().replace('.', '/') + ';';
		FieldInfo finfo = new FieldInfo(pool, DEFAULT_TARGET_NAME, handleType);
		finfo.setAccessFlags(AccessFlag.PRIVATE);
		cf.addField(finfo);

		// HashMap allMethods = getMethods(superClass, interfaces);
		// int size = allMethods.size();
		makeConstructors(classname, cf, pool, classname);

		ArrayList forwarders = new ArrayList();
		int s = overrideMethods(cf, pool, classname, forwarders);
		//addClassInitializer(cf, pool, classname, s, forwarders);
		addSetter(classname, cf, pool);
		/*if (!hasGetHandler)
			addGetter(classname, cf, pool);*/

		/*if (factoryWriteReplace) {
			try {
				cf.addMethod(makeWriteReplace(pool));
			} catch (DuplicateMemberException e) {
				// writeReplace() is already declared in the super
				// class/interfaces.
			}
		}*/

		thisClass = null;
		return cf;
	}

	private int overrideMethods(ClassFile cf, ConstPool pool,
			String classname, ArrayList forwarders) {
        String prefix = makeUniqueName("_d", signatureMethods);
        Iterator it = signatureMethods.iterator();
        int index = 0;
        while (it.hasNext()) {
            Map.Entry e = (Map.Entry)it.next();
            String key = (String)e.getKey();
            Method meth = (Method)e.getValue();
            /*if (ClassFile.MAJOR_VERSION < ClassFile.JAVA_5 || !isBridge(meth))
            	if (testBit(signature, index)) {
            		override(className, meth, prefix, index,
            				 keyToDesc(key, meth), cf, cp, forwarders);
            	}*/

            index++;
        }

        return index;
    }

	private void addSetter(String classname, ClassFile cf, ConstPool cp)
			throws CannotCompileException {
		String handleType = 'L' + superClass.getName().replace('.', '/') + ';';
		MethodInfo minfo = new MethodInfo(cp, DEFAULT_TARGET_SETTER, "("
				+ handleType + ")V");
		minfo.setAccessFlags(AccessFlag.PUBLIC);
		Bytecode code = new Bytecode(cp, 2, 2);
		code.addAload(0);
		code.addAload(1);
		code.addPutfield(classname, DEFAULT_TARGET_NAME, handleType);
		code.addOpcode(Bytecode.RETURN);
		minfo.setCodeAttribute(code.toCodeAttribute());
		cf.addMethod(minfo);
	}

	private void makeConstructors(String thisClassName, ClassFile cf,
			ConstPool cp, String classname) throws CannotCompileException {
		Constructor[] cons = getDeclaredConstructors(superClass);
		// legacy: if we are not caching then we need to initialise the default
		// handler
		// boolean doHandlerInit = !factoryUseCache;
		for (int i = 0; i < cons.length; i++) {
			Constructor c = cons[i];
			int mod = c.getModifiers();
			if (!Modifier.isFinal(mod) && !Modifier.isPrivate(mod)
					&& isVisible(mod, basename, c)) {
				MethodInfo m = makeConstructor(thisClassName, c, cp, superClass);
				cf.addMethod(m);
			}
		}
	}

	private static MethodInfo makeConstructor(String thisClassName,
			Constructor cons, ConstPool cp, Class superClass) {
		String desc = RuntimeSupport.makeDescriptor(cons.getParameterTypes(),
				Void.TYPE);
		MethodInfo minfo = new MethodInfo(cp, "<init>", desc);
		minfo.setAccessFlags(Modifier.PUBLIC); // cons.getModifiers() &
												// ~Modifier.NATIVE
		setThrows(minfo, cp, cons.getExceptionTypes());
		Bytecode code = new Bytecode(cp, 0, 0);

		// if caching is enabled then we don't have a handler to initialise so
		// this else branch will install
		// the handler located in the static field of class RuntimeSupport.
		/*
		 * code.addAload(0); code.addGetstatic(NULL_INTERCEPTOR_HOLDER,
		 * DEFAULT_INTERCEPTOR, HANDLER_TYPE); code.addPutfield(thisClassName,
		 * HANDLER, HANDLER_TYPE);
		 */
		int pc = code.currentPc();
		code.addAload(0);
		int s = addLoadParameters(code, cons.getParameterTypes(), 1);
		code.addInvokespecial(superClass.getName(), "<init>", desc);
		code.addOpcode(Opcode.RETURN);
		code.setMaxLocals(s + 1);
		CodeAttribute ca = code.toCodeAttribute();
		minfo.setCodeAttribute(ca);

		StackMapTable.Writer writer = new StackMapTable.Writer(32);
		writer.sameFrame(pc);
		ca.setAttribute(writer.toStackMapTable(cp));
		return minfo;
	}

	private void checkClassAndSuperName() {
		if (interfaces == null)
			interfaces = new Class[0];

		if (superClass == null) {
			superClass = OBJECT_TYPE;
			superName = superClass.getName();
			basename = interfaces.length == 0 ? superName : interfaces[0]
					.getName();
		} else {
			superName = superClass.getName();
			basename = superName;
		}

		if (Modifier.isFinal(superClass.getModifiers()))
			throw new RuntimeException(superName + " is final");

		if (basename.startsWith("java."))
			basename = "org.javassist.tmp." + basename;
	}

	public static UniqueName nameGenerator = new UniqueName() {
		private final String sep = "_$$Proxy"
				+ Integer.toHexString(this.hashCode() & 0xfff);
		private int counter = 0;

		public String get(String classname) {
			return classname + sep + Integer.toHexString(counter++);
		}
	};

	private static String makeProxyName(String classname) {
		synchronized (nameGenerator) {
			return nameGenerator.get(classname);
		}
	}
	private static String makeUniqueName(String name, List sortedMethods) {
        if (makeUniqueName0(name, sortedMethods.iterator()))
            return name;

        for (int i = 100; i < 999; i++) {
            String s = name + i;
            if (makeUniqueName0(s, sortedMethods.iterator()))
                return s;
        }

        throw new RuntimeException("cannot make a unique method name");
    }

    private static boolean makeUniqueName0(String name, Iterator it) {
        while (it.hasNext()) {
            Map.Entry e = (Map.Entry)it.next();
            String key = (String)e.getKey();
            if (key.startsWith(name))
                return false;
        }

        return true;
    }
	private static void setInterfaces(ClassFile cf, Class[] interfaces) {
		// String setterIntf = proxyClass.getName();
		String[] list;
		if (interfaces == null || interfaces.length == 0)
			list = new String[] {};
		else {
			list = new String[interfaces.length];
			for (int i = 0; i < interfaces.length; i++)
				list[i] = interfaces[i].getName();

			// list[interfaces.length] = setterIntf;
		}

		cf.setInterfaces(list);
	}

	protected ProtectionDomain getDomain() {
		Class clazz;
		if (superClass != null
				&& !superClass.getName().equals("java.lang.Object"))
			clazz = superClass;
		else if (interfaces != null && interfaces.length > 0)
			clazz = interfaces[0];
		else
			clazz = this.getClass();

		return clazz.getProtectionDomain();
	}

	private static int addLoadParameters(Bytecode code, Class[] params,
			int offset) {
		int stacksize = 0;
		int n = params.length;
		for (int i = 0; i < n; ++i)
			stacksize += addLoad(code, stacksize + offset, params[i]);

		return stacksize;
	}

	private static int addLoad(Bytecode code, int n, Class type) {
		if (type.isPrimitive()) {
			if (type == Long.TYPE) {
				code.addLload(n);
				return 2;
			} else if (type == Float.TYPE)
				code.addFload(n);
			else if (type == Double.TYPE) {
				code.addDload(n);
				return 2;
			} else
				code.addIload(n);
		} else
			code.addAload(n);

		return 1;
	}

	private static void setThrows(MethodInfo minfo, ConstPool cp, Method orig) {
		Class[] exceptions = orig.getExceptionTypes();
		setThrows(minfo, cp, exceptions);
	}

	private static void setThrows(MethodInfo minfo, ConstPool cp,
			Class[] exceptions) {
		if (exceptions.length == 0)
			return;

		String[] list = new String[exceptions.length];
		for (int i = 0; i < exceptions.length; i++)
			list[i] = exceptions[i].getName();

		ExceptionsAttribute ea = new ExceptionsAttribute(cp);
		ea.setExceptions(list);
		minfo.setExceptionsAttribute(ea);
	}

	/**
	 * Returns true if the method is visible from the package.
	 * 
	 * @param mod
	 *            the modifiers of the method.
	 */
	private static boolean isVisible(int mod, String from, Member meth) {
		if ((mod & Modifier.PRIVATE) != 0)
			return false;
		else if ((mod & (Modifier.PUBLIC | Modifier.PROTECTED)) != 0)
			return true;
		else {
			String p = getPackageName(from);
			String q = getPackageName(meth.getDeclaringClass().getName());
			if (p == null)
				return q == null;
			else
				return p.equals(q);
		}
	}

	private static String getPackageName(String name) {
		int i = name.lastIndexOf('.');
		if (i < 0)
			return null;
		else
			return name.substring(0, i);
	}

	static Constructor[] getDeclaredConstructors(final Class clazz) {
		if (System.getSecurityManager() == null)
			return clazz.getDeclaredConstructors();
		else {
			return (Constructor[]) AccessController
					.doPrivileged(new PrivilegedAction() {
						public Object run() {
							return clazz.getDeclaredConstructors();
						}
					});
		}
	}

	static Method[] getDeclaredMethods(final Class clazz) {
		if (System.getSecurityManager() == null)
			return clazz.getDeclaredMethods();
		else {
			return (Method[]) AccessController
					.doPrivileged(new PrivilegedAction() {
						public Object run() {
							return clazz.getDeclaredMethods();
						}
					});
		}
	}

}

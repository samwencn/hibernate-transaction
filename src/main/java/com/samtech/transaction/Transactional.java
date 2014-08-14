package com.samtech.transaction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Transactional {

	/**
	 * The timeout for this transaction.
	 * Defaults to the default timeout of the underlying transaction system.
	 * @see org.springframework.transaction.interceptor.TransactionAttribute#getTimeout()
	 */
	int timeout() default -1;

	/**
	 * {@code true} if the transaction is read-only.
	 * Defaults to {@code false}.
	 * <p>This just serves as a hint for the actual transaction subsystem;
	 * it will <i>not necessarily</i> cause failure of write access attempts.
	 * A transaction manager which cannot interpret the read-only hint will
	 * <i>not</i> throw an exception when asked for a read-only transaction.
	 * @see org.springframework.transaction.interceptor.TransactionAttribute#isReadOnly()
	 */
	boolean readOnly() default false;
}

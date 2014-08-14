package com.samtech.transaction;
  /**
   * 	
   * create object with transaction interceptor
   * and so just call one method
   *
   */
  public interface TransactionManageFactory {

	 <T> T createTransactionObject(Class<T> clazz);
	 /**
	  * 
	  * @param clazz
	  * @param ignoreMethodNames  not interceptor method
	  * @return
	  */
	 <T> T createTransactionObject(Class<T> clazz, String[] ignoreMethodNames);
}

/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.object.config;

import com.tc.object.bytecode.ClassAdapterFactory;

public interface IStandardDSOClientConfigHelper {

  // HACK: available only in IStandardDSOClientConfigHelper

  void allowCGLIBInstrumentation();

  // HACK: duplicated from DSOApplicationConfig

  void addRoot(String rootName, String rootFieldName);

  void addIncludePattern(String classPattern);

  void addWriteAutolock(String methodPattern);

  void addReadAutolock(String methodPattern);

  void addIncludePattern(String classname, boolean honorTransient);

  // HACK: duplicated from DSOClientConfigHelper  

  ITransparencyClassSpec getOrCreateSpec(String className);

  ITransparencyClassSpec getOrCreateSpec(String className, String applicator);

  void addCustomAdapter(String name, ClassAdapterFactory adapterFactory);

  void addIncludePattern(String expression, boolean honorTransient, boolean oldStyleCallConstructorOnLoad,
                         boolean honorVolatile);

  void addAutolock(String methodPattern, ConfigLockLevel type);
  
}

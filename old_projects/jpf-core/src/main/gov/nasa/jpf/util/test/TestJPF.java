//
// Copyright (C) 2006 United States Government as represented by the
// Administrator of the National Aeronautics and Space Administration
// (NASA).  All Rights Reserved.
// 
// This software is distributed under the NASA Open Source Agreement
// (NOSA), version 1.3.  The NOSA has been approved by the Open Source
// Initiative.  See the file NOSA-1.3-JPF at the top of the distribution
// directory tree for the complete NOSA document.
// 
// THE SUBJECT SOFTWARE IS PROVIDED "AS IS" WITHOUT ANY WARRANTY OF ANY
// KIND, EITHER EXPRESSED, IMPLIED, OR STATUTORY, INCLUDING, BUT NOT
// LIMITED TO, ANY WARRANTY THAT THE SUBJECT SOFTWARE WILL CONFORM TO
// SPECIFICATIONS, ANY IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR
// A PARTICULAR PURPOSE, OR FREEDOM FROM INFRINGEMENT, ANY WARRANTY THAT
// THE SUBJECT SOFTWARE WILL BE ERROR FREE, OR ANY WARRANTY THAT
// DOCUMENTATION, IF PROVIDED, WILL CONFORM TO THE SUBJECT SOFTWARE.
//
package gov.nasa.jpf.util.test;

import gov.nasa.jpf.Config;
import gov.nasa.jpf.JPF;
import gov.nasa.jpf.Error;
import gov.nasa.jpf.JPFShell;
import gov.nasa.jpf.jvm.*;

import gov.nasa.jpf.Property;
import gov.nasa.jpf.annotation.FilterField;
import gov.nasa.jpf.tool.RunTest;
import gov.nasa.jpf.util.FileUtils;
import gov.nasa.jpf.util.Misc;
import gov.nasa.jpf.util.Reflection;
import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;


import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.*;


/**
 * base class for JPF unit tests. TestJPF mostly includes JPF invocations
 * that check for occurrence or absence of certain execution results
 * 
 * This class can be used in two modes:
 *
 * <ol>
 * <li> wrapping a number of related tests for different SuTs into one class
 * (suite) that calls the various JPF runners with complete argument lists
 * (as in JPF.main(String[]args)) </li>
 *
 * <li> derive a class from TestJPF that uses the "..This" methods, which in
 * turn use reflection to automatically append the test class and method to the
 * JPF.main argument list (based on the calling class / method names). Note that
 * you have to obey naming conventions for this to work:
 *
 * <ul>
 * <li> the SuT class has to be the same as the test class without "Test", e.g.
 * "CastTest" -> "Cast" </li>
 * 
 * <li> the SuT method has to have the same name as the @Test method that
 * invokes JPF, e.g. "CastTest {.. @Test void testArrayCast() ..}" ->
 * "Cast {.. void testArrayCast()..} </li>
 *
 * </li>
 * </ol>
 */
public abstract class TestJPF implements JPFShell  {
  static PrintStream out = System.out;

  public static final String UNNAMED_PACKAGE = "";
  public static final String SAME_PACKAGE = null;



  //--- those are only used outside of JPF execution
  @FilterField protected static boolean runDirectly; // don't run test methods through JPF, invoke it directly
  @FilterField protected static boolean stopOnFailure; // stop as soon as we encounter a failed test or error
  @FilterField protected static boolean showConfig; // for debugging purposes
  @FilterField protected static boolean hideSummary;

  @FilterField protected String sutClassName;



  //--- internal methods

  public void fail (String msg, String[] args, String cause){
    StringBuilder sb = new StringBuilder();

    sb.append(msg);
    if (args != null){
      for (String s : args){
        sb.append(s);
        sb.append(' ');
      }
    }

    if (cause != null){
      sb.append(':');
      sb.append(cause);
    }

    fail(sb.toString());
  }

  public void fail (){
    throw new AssertionError();
  }

  public void fail (String msg){
    throw new AssertionError(msg);
  }

  public void report (String[] args) {
    out.print("  running jpf with args:");

    for (int i = 0; i < args.length; i++) {
      out.print(' ');
      out.print(args[i]);
    }

    out.println();
  }

  private String[] getArgsForCallerMethod (String[] jpfArgs){
    StackTraceElement callerEntry = Reflection.getCallerElement(2);

    String testMethod = callerEntry.getMethodName();
    String[] args = Misc.appendArray(jpfArgs, sutClassName, testMethod);

    return args;
  }

  /**
   * compute the SuT class name for a given JUnit test class: remove
   * optionally ending "..Test", and replace package (if specified)
   * 
   * @param testClass the JUnit test class
   * @param sutPackage optional SuT package name (without ending '.', null
   * os SAME_PACKAGE means same package, "" or UNNAMED_PACKAGE means unnamed package)
   * @return main class name of system under test
   */
  protected static String getSutClassName (String testClassName, String sutPackage){

    String sutClassName = testClassName;

    int i = sutClassName.lastIndexOf('.');
    if (i >= 0){  // testclass has a package

      if (sutPackage == null){   // use same package
        // nothing to do
      } else if (sutPackage.length() > 0) { // explicit sut package
        sutClassName = sutPackage + sutClassName.substring(i);

      } else { // unnamed sut package
        sutClassName = sutClassName.substring(i+1);
      }

    } else { // test class has no package
      if (sutPackage == null || sutPackage.length() == 0){   // use same package
        // nothing to do
      } else { // explicit sut package
        sutClassName = sutPackage + '.' + sutClassName;
      }
    }

    if (sutClassName.endsWith("JPF")) {
      sutClassName = sutClassName.substring(0, sutClassName.length() - 3);
    }

    return sutClassName;
  }

  // we can't set the sutClassName only from main() called methods (like
  // runTestsOfThisClass()) since main() doesn't get called if this is executed
  // by Ant (via <junit> task)
  // the default ctor is always executed
  public TestJPF () {
    sutClassName = getSutClassName(getClass().getName(), SAME_PACKAGE);
  }



  //------ the API to be used by subclasses

  /**
   * to be used from default ctor of derived class if the SuT is in a different
   * package
   * @param sutClassName the qualified SuT class name to be checked by JPF
   */
  protected TestJPF (String sutClassName){
    this.sutClassName = sutClassName;
  }

  public static boolean isJPFRun () {
    return false;
  }

  public static boolean isJUnitRun() {
    // intercepted by native peer if this runs under JPF
    Throwable t = new Throwable();
    t.fillInStackTrace();

    for (StackTraceElement se : t.getStackTrace()){
      if (se.getClassName().startsWith("org.junit.")){
        return true;
      }
    }

    return false;
  }

  public static boolean isRunTestRun() {
    // intercepted by native peer if this runs under JPF
    Throwable t = new Throwable();
    t.fillInStackTrace();

    for (StackTraceElement se : t.getStackTrace()){
      if (se.getClassName().equals("gov.nasa.jpf.tool.RunTest")){
        return true;
      }
    }

    return false;
  }


  protected static void getOptions (String[] args){
    runDirectly = false;
    showConfig = false;
    stopOnFailure = false;
    hideSummary = false;

    if (args != null){
      for (int i=0; i<args.length; i++){
        String a = args[i];
        if (a != null){
          if (a.length() > 0){
            if (a.charAt(0) == '-'){
              if (a.equals("-d")){
                runDirectly = true;
              } else if (a.equals("-s")){
                showConfig = true;
              } else if (a.equals("-x")){
                stopOnFailure = true;
              } else if (a.equals("-h")){
                hideSummary = true;
              }
              args[i] = null;

            } else {
              break; // done, this is a test method arg
            }
          }
        }
      }
    }
  }

  protected static boolean hasExplicitTestMethods(String[] args){
    for (String a : args){
      if (a != null){
        return true;
      }
    }

    return false;
  }

  protected static List<Method> getTestMethods(Class<? extends TestJPF> testCls, String[] args){
    List<Method> testMethods = new ArrayList<Method>();

    if (args != null && args.length > 0){
      for (String test : args){
        if (test != null){
          try {
            Method m = testCls.getDeclaredMethod(test);

            if (!m.isAnnotationPresent(org.junit.Test.class)) {
              throw new RuntimeException("test method does not have @Test annotation: " + test);
            }
            if (!Modifier.isPublic(m.getModifiers())) {
              throw new RuntimeException("test method not public: " + test);
            }
            if (Modifier.isStatic(m.getModifiers())) {
              throw new RuntimeException("test method is static: " + test);
            }
            if (m.getParameterTypes().length > 0) {
              throw new RuntimeException("test method requires arguments: " + test);
            }
            testMethods.add(m);

          } catch (NoSuchMethodException x) {
            throw new RuntimeException("method: " + test
                    + "() not in test class: " + testCls.getName(), x);
          }
        }
      }
    }

    if (testMethods.isEmpty()){
      for (Method m : testCls.getDeclaredMethods()) {
        int mod = m.getModifiers();
        if (m.getParameterTypes().length == 0
                && m.isAnnotationPresent(org.junit.Test.class)
                && Modifier.isPublic(mod) && !Modifier.isStatic(mod)) {
          testMethods.add(m);
        }
      }
    }

    return testMethods;
  }

  protected static void reportTestStart(String mthName){
    System.out.println();
    System.out.print("......................................... testing ");
    System.out.println(mthName);
  }

  protected static void reportTestFinished(String msg){
    System.out.print("......................................... ");
    System.out.println(msg);
  }

  protected static void reportResults(String clsName, int nTests, int nFailures, int nErrors, List<String> results){
    System.out.println();
    System.out.print("......................................... execution of testsuite: " + clsName);
    if (nFailures > 0 || nErrors > 0){
      System.out.println(" FAILED");
    } else if (nTests > 0) {
      System.out.println(" SUCCEEDED");
    } else {
      System.out.println(" OBSOLETE");
    }

    if (results != null){
      int i=0;
      for (String result : results){
        System.out.print(".... [" + ++i + "] ");
        System.out.println(result);
      }
    }

    System.out.print(".........................................");
    System.out.println(" tests: " + nTests + ", failures: " + nFailures + ", errors: " + nErrors);
  }

  /**
   * this is the main test loop if this TestJPF instance is executed directly
   * or called from RunTest. It is *not* called if this is executed from JUnit
   */
  protected static void runTests (Class<? extends TestJPF> testCls, String... args){
    int nTests = 0;
    int nFailures = 0;
    int nErrors = 0;
    String testMethodName = null;
    List<String> results = null;

    getOptions(args);

    try {
      List<Method> testMethods = getTestMethods(testCls, args);
      results = new ArrayList<String>(testMethods.size());

      for (Method testMethod : testMethods) {
        testMethodName = testMethod.getName();
        String result = testMethodName;
        try {
          Object testObject = testCls.newInstance();

          nTests++;
          reportTestStart( testMethodName);

          testMethod.invoke(testObject);
          result += ": Ok";

        } catch (InvocationTargetException x) {
          Throwable cause = x.getCause();
cause.printStackTrace();
          if (cause instanceof AssertionError) {
            nFailures++;
            reportTestFinished("test method failed with: " + cause.getMessage());
            result += ": Failed";

          } else {
            nErrors++;
            reportTestFinished("unexpected error while executing test method: " + cause.getMessage());
            result += ": Error";
          }

          if (stopOnFailure){
            break;
          }
        }

        results.add(result);
        reportTestFinished(result);
      }

    //--- those exceptions are unexpected and represent unrecoverable test harness errors
    } catch (InstantiationException x) {
      nErrors++;
      reportTestFinished("TEST ERROR: cannot instantiate test class: " + x.getMessage());
    } catch (IllegalAccessException x) { // can't happen if getTestMethods() worked
      nErrors++;
      reportTestFinished("TEST ERROR: method not public: " + testMethodName);
    } catch (IllegalArgumentException x) {  // can't happen if getTestMethods() worked
      nErrors++;
      reportTestFinished("TEST ERROR: illegal argument for test method: " + testMethodName);
    } catch (RuntimeException rx) {
      nErrors++;
      reportTestFinished("TEST ERROR: " + rx.toString());
    }

    if (!hideSummary){
      reportResults(testCls.getName(), nTests, nFailures, nErrors, results);
    }

    if (nErrors > 0 || nFailures > 0){
      if (isRunTestRun()){
        // we need to reportTestFinished this test has failed
        throw new RunTest.Failed();
      }
    }
  }


  /**
   * NOTE: this needs to be called from the concrete test class, typically from
   * its main() method, otherwise we don't know the name of the class we have
   * to pass to JPF
   */
  protected static void runTestsOfThisClass (String[] testMethods){
    // needs to be at the same stack level, so we can't delegate
    Class<? extends TestJPF> testClass = Reflection.getCallerClass(TestJPF.class);
    runTests(testClass, testMethods);
  }

  protected JPF createAndRunJPF (String[] args){
    JPF jpf = null;

    Config conf = new Config(args);

    // if we have any specific test property overrides, do so
    conf.promotePropertyCategory("test.");

    if (conf.getTarget() != null) {
      jpf = new JPF(conf);

      if (showConfig) {
        conf.print(new PrintWriter(System.out));
      }

      JPF_gov_nasa_jpf_util_test_TestJPF.init();

      jpf.run();
    }

    return jpf;
  }


  //--- the JPFShell interface
  public void start(String[] testMethods){
    Class<? extends TestJPF> testClass = getClass(); // this is an instance method
    runTests(testClass, testMethods);
  }


  //--- the JPF run test methods

  /**
   * run JPF expecting a AssertionError in the SuT
   * @param args JPF main() arguments
   */
  protected JPF assertionError (String details, String... args) {
    return unhandledException("java.lang.AssertionError", details, args );
  }
  protected boolean verifyAssertionErrorDetails (String details, String... args){
    if (runDirectly) {
      return true;
    } else {
      StackTraceElement caller = Reflection.getCallerElement();
      args = Misc.appendArray(args, caller.getClassName(), caller.getMethodName());
      unhandledException("java.lang.AssertionError", details, args);
      return false;
    }
  }
  protected boolean verifyAssertionError (String... args){
    if (runDirectly) {
      return true;
    } else {
      StackTraceElement caller = Reflection.getCallerElement();
      args = Misc.appendArray(args, caller.getClassName(), caller.getMethodName());
      unhandledException("java.lang.AssertionError", null, args);
      return false;
    }
  }

  /**
   * run JPF expecting no SuT property violations or JPF exceptions
   * @param args JPF main() arguments
   */
  protected JPF noPropertyViolation (String... args) {
    JPF jpf = null;

    report(args);

    try {
      jpf = createAndRunJPF(args);
    } catch (Throwable t) {
      // we get as much as one little hickup and we declare it failed
      t.printStackTrace();
      fail("JPF internal exception executing: ", args, t.toString());
      return jpf;
    }

    List<Error> errors = jpf.getSearchErrors();
    if ((errors != null) && (errors.size() > 0)) {
      fail("JPF found unexpected errors: " + (errors.get(0)).getDescription());
    }

    JVM vm = jpf.getVM();
    if (vm != null) {
      ExceptionInfo xi = vm.getPendingException();
      if (xi != null) {
        xi.printOn(new PrintWriter(System.out));
        fail("JPF caught exception executing: ", args, xi.getExceptionClassname());
      }
    }

    return jpf;
  }
  protected boolean verifyNoPropertyViolation (String...jpfArgs){
    if (runDirectly) {
      return true;
    } else {
      StackTraceElement caller = Reflection.getCallerElement();
      String[] args = Misc.appendArray(jpfArgs, caller.getClassName(), caller.getMethodName());
      noPropertyViolation(args);
      return false;
    }
  }

  /**
   * NOTE: this uses the exception class name because it might be an
   * exception type that is only known to JPF (i.e. not in the native classpath)
   *
   * @param xClassName name of the exception base type that is expected
   * @param details detail message of the expected exception
   * @param args JPF arguments
   */
  protected JPF unhandledException ( String xClassName, String details, String... args) {
    JPF jpf = null;

    report(args);

    try {
      jpf = createAndRunJPF(args);
    } catch (Throwable t) {
      t.printStackTrace();
      fail("JPF internal exception executing: ", args, t.toString());
      return jpf;
    }

    ExceptionInfo xi = JVM.getVM().getPendingException();
    if (xi == null) {
      fail("JPF failed to catch exception executing: ", args, ("expected " + xClassName));
    } else {
      String xn = xi.getExceptionClassname();
      if (!xn.equals(xClassName)) {
        fail("JPF caught wrong exception: " + xn + ", expected: " + xClassName);
      }
    }

    return jpf;
  }
  protected boolean verifyUnhandledExceptionDetails (String xClassName, String details, String... args){
    if (runDirectly) {
      return true;
    } else {
      StackTraceElement caller = Reflection.getCallerElement();
      args = Misc.appendArray(args, caller.getClassName(), caller.getMethodName());
      unhandledException(xClassName, details, args);
      return false;
    }
  }
  protected boolean verifyUnhandledException (String xClassName, String... args){
    if (runDirectly) {
      return true;
    } else {
      StackTraceElement caller = Reflection.getCallerElement();
      args = Misc.appendArray(args, caller.getClassName(), caller.getMethodName());
      unhandledException(xClassName, null, args);
      return false;
    }
  }


  /**
   * run JPF expecting it to throw an exception
   * NOTE - xClassName needs to be the concrete exception, not a super class
   * @param args JPF main() arguments
   */
  protected JPF jpfException (Class<? extends Throwable> xCls, String... args) {
    JPF jpf = null;
    Throwable exception = null;

    report(args);

    try {
      jpf = createAndRunJPF( args);
    } catch (JPF.ExitException xx) {
      exception = xx.getCause();
    } catch (Throwable x) {
      exception = x;
    }

    if (exception != null){
      if (!xCls.isAssignableFrom(exception.getClass())){
        fail("JPF produced wrong exception: " + exception + ", expected: " + xCls.getName());
      }
    } else {
      fail("JPF failed to produce exception, expected: " + xCls.getName());
    }

    return jpf;
  }
  protected boolean verifyJPFException (Class<? extends Throwable> xCls, String... args){
    if (runDirectly) {
      return true;
    } else {
      StackTraceElement caller = Reflection.getCallerElement();
      args = Misc.appendArray(args, caller.getClassName(), caller.getMethodName());
      jpfException(xCls, args);
      return false;
    }
  }

  
  
  /**
   * run JPF expecting a property violation of the SuT
   * @param args JPF main() arguments
   */
  protected JPF propertyViolation (Class<? extends Property> propertyCls, String... args ){
    JPF jpf = null;

    report(args);

    try {
      jpf = createAndRunJPF( args);
    } catch (Throwable t) {
      t.printStackTrace();
      fail("JPF internal exception executing: ", args, t.toString());
    }

    List<Error> errors = jpf.getSearchErrors();
    if (errors != null) {
      for (Error e : errors) {
        if (propertyCls == e.getProperty().getClass()) {
          return jpf; // success, we got the sucker
        }
      }
    }

    fail("JPF failed to detect error: " + propertyCls.getName());
    return jpf;
  }
  protected boolean verifyPropertyViolation (Class<? extends Property> propertyCls, String... args){
    if (runDirectly) {
      return true;
    } else {
      StackTraceElement caller = Reflection.getCallerElement();
      args = Misc.appendArray(args, caller.getClassName(), caller.getMethodName());
      propertyViolation(propertyCls, args);
      return false;
    }
  }


  /**
   * run JPF expecting a deadlock in the SuT
   * @param args JPF main() arguments
   */
  protected JPF deadlock (String... args) {
    return propertyViolation(NotDeadlockedProperty.class, args );
  }
  protected boolean verifyDeadlock (String... args){
    if (runDirectly) {
      return true;
    } else {
      StackTraceElement caller = Reflection.getCallerElement();
      args = Misc.appendArray(args, caller.getClassName(), caller.getMethodName());
      propertyViolation(NotDeadlockedProperty.class, args);
      return false;
    }
  }

  // these are the org.junit.Assert APIs, but we don't want org.junit to be
  // required to run tests

  public static void assertEquals(String msg, Object expected, Object actual){
    if (expected == null || actual == null){
      assert expected == actual : msg;
    } else {
      assert expected.equals(actual) : msg;
    }
  }

  public static void assertEquals(Object expected, Object actual){
    if (expected == null || actual == null){
      assert expected == actual;
    } else {
      assert expected.equals(actual);
    }
  }
  public static void assertEquals(int expected, int actual){
    assert expected == actual;
  }
  public static void assertEquals(long expected, long actual){
    assert expected == actual;
  }
  public static void assertEquals(double expected, double actual){
    assert false : "identity comparison of floating point values";
  }
  public static void assertEquals(float expected, float actual){
    assert false : "identity comparison of floating point values";
  }
  public static void assertEquals(double expected, double actual, double delta){
    assert Math.abs(expected - actual) <= delta;
  }
  public static void assertEquals(float expected, float actual, float delta){
    assert Math.abs(expected - actual) <= delta;
  }


  public static void assertNotNull(Object o){
    assert o != null;
  }
  public static void assertNull(Object o){
    assert o == null;
  }
  public static void assertSame(Object expected, Object actual){
    assert expected == actual;
  }

  public static void assertFalse (String msg, boolean cond){
    assert !cond : msg;
  }
  public static void assertFalse (boolean cond){
    assert !cond;
  }
  public static void assertTrue (String msg, boolean cond){
    assert cond : msg;
  }
  public static void assertTrue (boolean cond){
    assert cond;
  }

}

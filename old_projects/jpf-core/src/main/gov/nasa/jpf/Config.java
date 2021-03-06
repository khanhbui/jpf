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
package gov.nasa.jpf;


import gov.nasa.jpf.util.FileUtils;
import gov.nasa.jpf.util.JPFSiteUtils;
import gov.nasa.jpf.util.Misc;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * class that encapsulates property-based JPF configuration. This is mainly an
 * associative array with various typed accessors, and a structured
 * initialization process. This implementation has the design constraint that it
 * does not promote symbolic information to concrete types, which means that
 * frequently accessed data should be promoted and cached in client classes.
 * This in turn means we assume the data is not going to change at runtime.
 * Major motivation for this mechanism is to avoid 'Option' classes that have
 * concrete type fields, and hence are structural bottlenecks, i.e. every
 * parameterized user extension (Heuristics, Scheduler etc.) require to update
 * this single class. Note that Config is also not thread safe with respect to
 * retrieving exceptions that occurred during instantiation
 *
 * Another important caveat for both implementation and usage of Config is that
 * it is supposed to be our master configuration mechanism, i.e. it is also used
 * to configure other core services like logging. This means that Config
 * initialization should not depend on these services. Initialization has to
 * return at all times, recording potential problems for later handling. This is
 * why we have to keep the Config data model and initialization fairly simple
 * and robust.
 *
 * Except of JPF and Config itself, all JPF classes are loaded by a
 * Classloader that is constucted by Config (e.g. by collecting jars from
 * known/configured locations), i.e. we SHOULD NOT rely on any 3rd party
 * libraries within Config. The class should be as autarkical as possible.
 *
 *
 * PROPERTY SOURCES
 * ----------------
 *
 * (1) one site.properties - this file specifies the location of the jpf-core and
 * installed extensions, like:
 *
 *     jpf-core = /Users/pcmehlitz/projects/jpf/jpf-core
 *     ...
 *     jpf-numeric = /Users/pcmehlitz/projects/jpf/jpf-numeric
 *     ...
 *     extensions = ${jpf-core}
 *
 * Each key/directory that is in site.properties is used to locate a corresponding
 * project property (jpf.properties) file
 *
 * (2) any number of jpf.properties project properties files - each directory
 * entry in the 'extensions' list is checked for a jpf.properties file, which 
 * is automatically loaded if found. Project properties mostly contain path 
 * settings that are used to initialize class loading by the host VM and JPF
 *
 * (3) one *.jpf application properties - this specifies all the settings for a
 * specific JPF run, esp. listener and target/target_args.
 * app properties can be specified as the sole JPF argument, i.e. instead of
 * a SUT classname
 *     ..
 *     target = x.Y.MySystemUnderTest
 *     target_args = one,two
 *     ..
 *     listener = z.MyListener
 *
 * (4) commandline properties - all start with '+', they can override all other props
 *
 *
 * LOOKUP ORDER
 * ------------
 *                       property lookup
 *   property type   :      spec             :  default
 *   ----------------:-----------------------:----------
 * |  site           :   +site               : "${user.home}/[.]jpf/site.properties"
 * |                 :                       :
 * |  project        :  'extensions' value   : set in site.properties
 * |                 :                       :
 * |  app            :   +app                : -
 * |                 :                       :
 * v  cmdline        :   +<key>=<val>        : -
 *
 * * if there is an explicit spec and the pathname does not exist, throw a
 * JPFConfigException
 *
 * * if the system properties cannot be found, throw a JPFConfigException
 *
 *
 * <2do> need to make NumberFormatException handling consistent - should always
 * throw an JPFConfigException, not silently returning the default value
 *
 */


@SuppressWarnings("serial")
public class Config extends Properties {

  static final String TARGET_KEY = "target";
  static final String TARGET_ARGS_KEY = "target_args";

  static final char   KEY_PREFIX = '@';
  static final String REQUIRES_KEY = "@requires";
  static final String INCLUDE_KEY = "@include";
  static final String INCLUDE_UNLESS_KEY = "@include_unless";
  static final String INCLUDE_IF_KEY = "@include_if";
  static final String USING_KEY = "@using";

  static final String[] EMPTY_STRING_ARRAY = new String[0];

  public static final String LIST_SEPARATOR = ",";
  static final String PATH_SEPARATOR = ","; // the default for automatic appends

  static final Class<?>[] CONFIG_ARGTYPES = { Config.class };  
  static final Class<?>[] NO_ARGTYPES = new Class<?>[0];
  static final Object[] NO_ARGS = new Object[0];

  static final String TRUE = "true";
  static final String FALSE = "false";

  static final String IGNORE_VALUE = "-";

  // do we want to log the config init
  public static boolean log = false;

  // bad - a control exception
  static class MissingRequiredKeyException extends RuntimeException {
    MissingRequiredKeyException(String details){
      super(details);
    }
  }

  ClassLoader loader = Config.class.getClassLoader();
  
  // where did we initialize from
  ArrayList<Object> sources = new ArrayList<Object>();
  
  List<ConfigChangeListener> changeListeners;
  
  // Properties are simple Hashmaps, but we want to maintain the order of entries
  LinkedList<String> entrySequence = new LinkedList<String>();

  // an [optional] hashmap to keep objects we want to be singletons
  HashMap<String,Object> singletons;
  
  final Object[] CONFIG_ARGS = { this };

  String[] args; // our original (non-nullified) command line args


  public Config (String[] args)  {
    this.args = args;
    String[] a = args.clone(); // we might nullify some of them

    String appProperties = getAppPropertiesLocation(a);
    String siteProperties = getSitePropertiesLocation(a, appProperties);

    //--- the site properties
    if (siteProperties != null){
      loadProperties( siteProperties);
    }

    //--- get the project properties from current dir + site configured extensions
    loadProjectProperties();

    //--- the application properties
    if (appProperties != null){
      loadProperties( appProperties);
    }

    //--- at last, the (rest of the) command line properties
    loadArgs(a);
    
    // compute the global 'native_classpath', 'classpath', 'sourcepath' and 'peer_packages'
    collectGlobalPaths();

    //printEntries();
  }

  private Config() {
    // just interal, for reloading
  }

  public static void enableLogging (boolean enableLogging){
    log = enableLogging;
  }

  public void log (String msg){
    if (log){ // very simplisitc, but we might do more in the future
      System.out.println(msg);
    }
  }


  String getAppPropertiesLocation(String[] args){
    String path = null;

    path = getPathArg(args, "app");
    if (path == null){
      // see if the first free arg is a *.jpf
      path = getAppArg(args);
    }

    return path;
  }

  String getSitePropertiesLocation(String[] args, String appPropPath){
    String path = getPathArg(args, "site");

    if (path == null){
      // look into the app properties
      // NOTE: we might want to drop this in the future because it constitutes
      // a cyclic properties file dependency
      if (appPropPath != null){
        path = JPFSiteUtils.getMatchFromFile(appPropPath,"site");
      }

      if (path == null) {
        // fall back to ${user.home}/[.]jpf/site.properties
        String userHome = System.getProperty("user.home");
        File f = new File(userHome, "jpf/site.properties");
        if (!f.isFile()) {
          f = new File(userHome, ".jpf/site.properties");
        }
        if (f.isFile()) {
          path = f.getAbsolutePath();
        }
      }
    }

    return path;
  }


  // watch out - this does not reset the computed paths!
  public Config reload() {
    log("reloading config");

    // just reload all our sources
    Config newConfig = new Config();
    for (Object src : sources){
      if (src instanceof File) {
        newConfig.loadProperties(((File)src).getPath());
      } else if (src instanceof URL) {
        newConfig.loadProperties((URL)src);
      } else {
        log("don't know how to reload: " + src);
      }
    }

    // now reload command line args on top of that
    newConfig.loadArgs(args);
    newConfig.args = args;
    
    return newConfig;
  }

  public String[] getArgs() {
    return args;
  }

  /*
   * note that matching args are expanded and stored here, to avoid any
   * discrepancy whith value expansions (which are order-dependent)
   */
  protected String getPathArg (String[] args, String key){
    int keyLen = key.length();

    for (int i=0; i<args.length; i++){
      String a = args[i];
      if (a != null){
        int len = a.length();
        if (len > keyLen + 2){
          if (a.charAt(0) == '+' && a.charAt(keyLen+1) == '='){
            if (a.substring(1, keyLen+1).equals(key)){
              args[i] = null; // processed
              String val = expandString(key, a.substring(keyLen+2));
              setProperty(key, val);
              return val;
            }
          }
        }
      }
    }

    return null;
  }

  /*
   * if the first freeArg is a JPF application property filename, use this
   * as targetArg and set the "jpf.app" property accordingly
   */
  protected String getAppArg (String[] args){

    for (int i=0; i<args.length; i++){
      String a = args[i];
      if (a != null && a.length() > 0){
        switch (a.charAt(0)) {
          case '+': continue;
          case '-': continue;
          default:
            if (a.endsWith(".jpf")){
              String val = expandString("jpf.app", a);
              put("jpf.app", val);
              args[i] = null; // processed
              return val;
            }
        }
      }
    }

    return null;
  }


  protected void loadProperties (URL url){
    log("loading defaults from: " + url);

    InputStream is = null;
    try {
      is = url.openStream();
      load(is);
      sources.add(url);
    } catch (IOException iox){
      log("error in input stream for: " + url + " : " + iox.getMessage());
    } finally {
      if (is != null){
        try {
          is.close();
        } catch (IOException iox1){
          log("error closing input stream for: " + url + " : " + iox1.getMessage());
        }
      }
    }
  }

  protected void setConfigPathProperties (String fileName){
    put("config", fileName);
    int i = fileName.lastIndexOf(File.separatorChar);
    if (i>=0){
      put("config_path", fileName.substring(0,i));
    } else {
      put("config_path", ".");
    }
  }


  protected boolean loadProperties (String fileName) {
    if (fileName != null && fileName.length() > 0) {
      FileInputStream is = null;
      try {
        File f = new File(fileName);
        if (f.isFile()) {
          log("loading property file: " + fileName);

          setConfigPathProperties(f.getAbsolutePath());
          sources.add(f);
          is = new FileInputStream(f);
          load(is);
          return true;
        } else {
          throw exception("property file does not exist: " + f.getAbsolutePath());
        }
      } catch (MissingRequiredKeyException rkx){
        // Hmpff - control exception
        log("missing required key: " + rkx.getMessage() + ", skipping: " + fileName);
      } catch (IOException iex) {
        throw exception("error reading properties: " + fileName);
      } finally {
        if (is != null){
          try {
            is.close();
          } catch (IOException iox1){
            log("error closing input stream for file: " + fileName);
          }
        }
      }
    }

    return false;
  }


  /**
   * this holds the policy defining in which order we process directories
   * containing JPF projects (i.e. jpf.properties files)
   */
  protected void loadProjectProperties () {
    // this is the list of directories holding jpf.properties files that
    // have to be processed in order of entry (increasing priority)
    LinkedList<File> jpfDirs = new LinkedList<File>();

    // deduce the JPF projects in use (at least jpf-core) from the CL which
    // defined this class
    addJPFdirsFromClasspath(jpfDirs);

    // add all the site configured extension dirs (but NOT jpf-core)
    addJPFdirsFromSiteExtensions(jpfDirs);

    // add the current dir, which has highest priority (this might bump up
    // a previous entry by reodering it - which includes jpf-core)
    addCurrentJPFdir(jpfDirs);

    // now load all the jpf.property files we found in these dirs
    // (later loads can override previous settings)
    for (File dir : jpfDirs){
      loadProperties(new File(dir,"jpf.properties").getAbsolutePath());
    }
  }

  protected void appendPath (String pathKey, String key, String configPath){
    String[] paths = getStringArray(key);
    if (paths != null){
      for (String e : paths) {
        if (!e.startsWith("${") || !e.startsWith(File.separator)) {
          e = configPath + File.separatorChar + e;
        }
        append(pathKey, e, PATH_SEPARATOR);
      }
    }
  }

  protected void addJPFdirs (List<File> jpfDirs, File dir){
    while (dir != null) {
      File jpfProp = new File(dir, "jpf.properties");
      if (jpfProp.isFile()) {
        registerJPFdir(jpfDirs, dir);
        return;       // we probably don't want recursion here
      }
      dir = getParentFile(dir);
    }
  }

  /**
   * add the current dir to the list of JPF components.
   * Note: this includes the core, so that we maintain the general
   * principle that the enclosing project takes precedence (imagine the opposite:
   * if we want to test a certain feature that is overridden by another extension
   * we don't know about)
   */
  protected void addCurrentJPFdir(List<File> jpfDirs){
    File dir = new File(System.getProperty("user.dir"));
    while (dir != null) {
      File jpfProp = new File(dir, "jpf.properties");
      if (jpfProp.isFile()) {
        registerJPFdir(jpfDirs, dir);
        return;
      }
      dir = getParentFile(dir);
    }
  }

  protected void addJPFdirsFromClasspath(List<File> jpfDirs) {
    String cp = System.getProperty("java.class.path");
    String[] cpEntries = cp.split(File.pathSeparator);

    for (String p : cpEntries) {
      File f = new File(p);
      File dir = f.isFile() ? getParentFile(f) : f;

      addJPFdirs(jpfDirs, dir);
    }
  }

  protected void addJPFdirsFromSiteExtensions (List<File> jpfDirs){
    String[] extensions = getCompactStringArray("extensions");
    if (extensions != null){
      for (String pn : extensions){
        addJPFdirs( jpfDirs, new File(pn));
      }
    }
  }

  /**
   * the obvious part is that it only adds to the list if the file is absent
   * the not-so-obvious part is that it re-orders already present files
   * to maintain the priority
   */
  protected boolean registerJPFdir(List<File> list, File dir){
    try {
      dir = dir.getCanonicalFile();

      for (File e : list) {
        if (e.equals(dir)) {
          list.remove(e);
          list.add(e);
          return false;
        }
      }
    } catch (IOException iox) {
      throw new JPFConfigException("illegal path spec: " + dir);
    }
    
    list.add(dir);
    return true;
  }

  static File root = new File(File.separator);

  protected File getParentFile(File f){
    if (f == root){
      return null;
    } else {
      File parent = f.getParentFile();
      if (parent == null){
        parent = new File(f.getAbsolutePath());

        if (parent.getName().equals(root.getName())) {
          return root;
        } else {
          return parent;
        }
      } else {
        return parent;
      }
    }
  }


  /*
   * argument syntax:
   *          {'+'<key>['='<val>'] | '-'<driver-arg>} {<free-arg>}
   *
   * (1) null args are ignored
   * (2) all config args start with '+'
   * (3) if '=' is ommitted, a 'true' value is assumed
   * (4) if <val> is ommitted, a 'null' value is assumed
   * (5) no spaces around '='
   * (6) all '-' driver-args are ignored
   * (7) if 'target' is already set (from 'jpf.app' property or
   *     "*.jpf" free-arg), all remaining <free-args> are 'target_args'
   *     otherwise 'target' is set to the first free-arg
   */

  protected void loadArgs (String[] args) {

    for (int i=0; i<args.length; i++){
      String a = args[i];

      if (a != null && a.length() > 0){
        switch (a.charAt(0)){
          case '+': // Config arg
            processArg(a.substring(1));
            break;

          case '-': // driver arg, ignore
            continue;

          default:  // target args to follow

            if (getString(TARGET_KEY) == null){ // no 'target' yet
              setTarget(a);
              i++;
            }

            int n = args.length - i;
            if (n > 0){ // we (might) have 'target_args'
              String[] targetArgs = new String[n];
              System.arraycopy(args, i, targetArgs, 0, n);
              setTargetArgs(targetArgs);
            }

            return;
        }
      }
    }
  }


  /*
   * this does not include the '+' prefix, just the 
   *     <key>[=[<value>]]
   */
  protected void processArg (String a) {

    int idx = a.indexOf("=");

    if (idx == 0){
      throw new JPFConfigException("illegal option: " + a);
    }

    if (idx > 0) {
      String key = a.substring(0, idx).trim();
      String val = a.substring(idx + 1).trim();

      if (val.length() == 0){
        val = null;
      }

      setProperty(key, val);

    } else {
      setProperty(a.trim(), "true");
    }

  }


  /**
   * replace string constants with global static objects
   */
  protected String normalize (String v) {
    if (v == null){
      return null; // ? maybe TRUE - check default loading of "key" or "key="
    }

    // trim leading and trailing blanks (at least Java 1.4.2 does not take care of trailing blanks)
    v = v.trim();
    
    // true/false
    if ("true".equalsIgnoreCase(v) || "t".equalsIgnoreCase(v)
        || "yes".equalsIgnoreCase(v) || "y".equalsIgnoreCase(v)
        || "on".equalsIgnoreCase(v)) {
      v = TRUE;
    } else if ("false".equalsIgnoreCase(v) || "f".equalsIgnoreCase(v)
        || "no".equalsIgnoreCase(v) || "n".equalsIgnoreCase(v)
        || "off".equalsIgnoreCase(v)) {
      v = FALSE;
    }

    // nil/null
    if ("nil".equalsIgnoreCase(v) || "null".equalsIgnoreCase(v)){
      v = null;
    }
    
    return v;
  }

  
  // our internal expander
  // Note that we need to know the key this came from, to handle recursive expansion
  protected String expandString (String key, String s) {
    int i, j = 0;
    if (s == null || s.length() == 0) {
      return s;
    }

    while ((i = s.indexOf("${", j)) >= 0) {
      if ((j = s.indexOf('}', i)) > 0) {
        String k = s.substring(i + 2, j);
        String v;
        
        if ((key != null) && key.equals(k)) {
          // that's expanding itself -> use what is there
          v = getProperty(key);
        } else {
          // refers to another key, which is already expanded, so this
          // can't get recursive (we expand during entry storage)
          v = getProperty(k);
        }
        
        if (v == null) { // if we don't have it, fall back to system properties
          v = System.getProperty(k);
        }
        
        if (v != null) {
          s = s.substring(0, i) + v + s.substring(j + 1, s.length());
          j = i + v.length();
        } else {
          s = s.substring(0, i) + s.substring(j + 1, s.length());
          j = i;
        }
      }
    }

    return s;    
  }


  boolean loadPropertiesRecursive (String fileName){
    // save the current values of automatic properties
    String curConfig = (String)get("config");
    String curConfigPath = (String)get("config_path");

    File propFile = new File(fileName);
    if (!propFile.isAbsolute()){
      propFile = new File(curConfigPath, fileName);
    }
    String absPath = propFile.getAbsolutePath();

    if (!propFile.isFile()){
      throw exception("property file does not exist: " + absPath);
    }

    boolean ret = loadProperties(absPath);

    // restore the automatic properties
    super.put("config", curConfig);
    super.put("config_path", curConfigPath);

    return ret;
  }

  void includePropertyFile(String key, String value){
    value = expandString(key, value);
    if (value != null && value.length() > 0){
      loadPropertiesRecursive(value);
    } else {
      throw exception("@include pathname argument missing");
    }
  }

  void includeCondPropertyFile(String key, String value, boolean keyPresent){
    value = expandString(key, value);
    if (value != null && value.length() > 0){
      // check if it's a conditional "@include_unless/if = ?key?pathName"
      if (value.charAt(0) == '?'){
        int idx = value.indexOf('?', 1);
        if (idx > 1){
          String k = value.substring(1, idx);
          if (containsKey(k) == keyPresent){
            String v = value.substring(idx+1);
            if (v.length() > 0){
              loadPropertiesRecursive(v);
            } else {
              throw exception("@include_unless pathname argument missing (?<key>?<pathName>)");
            }
          }

        } else {
          throw exception("malformed @include_unless argument (?<key>?<pathName>), found: " + value);
        }
      } else {
        throw exception("malformed @include_unless argument (?<key>?<pathName>), found: " + value);
      }
    } else {
      throw exception("@include_unless missing ?<key>?<pathName> argument");
    }
  }


  void includeProjectPropertyFile (String projectId){
    String projectPath = getString(projectId);
    if (projectPath != null){
      File projectProps = new File(projectPath, "jpf.properties");
      if (projectProps.isFile()){
        loadPropertiesRecursive(projectProps.getAbsolutePath());

      } else {
        throw exception("project properties not found: " + projectProps.getAbsolutePath());
      }

    } else {
      throw exception("unknown project id (check site.properties): " + projectId);
    }
  }

  // we override this so that we can handle expansion for both key and value
  // (value expansion can be recursive, i.e. refer to itself)
  @Override
  public Object put (Object keyObject, Object valueObject){

    if (keyObject == null){
      throw exception("no null keys allowed");
    } else if (!(keyObject instanceof String)){
      throw exception("only String keys allowed, got: " + keyObject);
    }
    if (valueObject != null && !(valueObject instanceof String)){
      throw exception("only String or null values allowed, got: " + valueObject);
    }

    String key = (String)keyObject;
    String value = (String)valueObject;

    if (key.length() == 0){
      throw exception("no empty keys allowed");
    }

    if (key.charAt(0) == KEY_PREFIX){

      if (REQUIRES_KEY.equals(key)) {
        // shortcircuit loading of property files - used to enforce order
        // of properties, e.g. to model dependencies
        for (String reqKey : split(value)) {
          if (!containsKey(reqKey)) {
            throw new MissingRequiredKeyException(reqKey);
          }
        }
        return null;
        
      } else if (INCLUDE_KEY.equals(key)) {
        includePropertyFile(key, value);
        return null;
      } else if (INCLUDE_UNLESS_KEY.equals(key)) {
        includeCondPropertyFile(key, value, false);
        return null;
      } else if (INCLUDE_IF_KEY.equals(key)) {
        includeCondPropertyFile(key, value, true);
        return null;
      } else if (USING_KEY.equals(key)){
        includeProjectPropertyFile(value);
        return null;
      } else {
        throw exception("unknown keyword: " + key);
      }


    } else {
      // finally, a real key/value pair to add (or remove) - expand and store
      String k = expandString(null, key);

      if (!(value == null)) { // add or overwrite entry
        String v = (String) value;

        if (k.charAt(k.length() - 1) == '+') { // the append hack
          k = k.substring(0, k.length() - 1);
          return append(k, v, null);

        } else if (k.charAt(0) == '+') { // the prepend hack
          k = k.substring(1);
          return prepend(k, v, null);

        } else { // normal value set
          v = normalize(expandString(k, v));
          if (v != null){
            return setKey(k, v);
          } else {
            return removeKey(k);
          }
        }

      } else { // setting a null value removes the entry
        return removeKey(k);
      }
    }
  }

  private Object setKey (String k, String v){
    Object oldValue = put0(k, v);
    notifyPropertyChangeListeners(k, (String) oldValue, v);
    return oldValue;
  }

  private Object removeKey (String k){
    Object oldValue = super.get(k);
    remove0(k);
    notifyPropertyChangeListeners(k, (String) oldValue, null);
    return oldValue;
  }

  private Object put0 (String k, Object v){
    entrySequence.add(k);
    return super.put(k, v);
  }

  private Object remove0 (String k){
    entrySequence.add(k);
    return super.remove(k);
  }

  protected String prepend (String key, String value, String separator) {
    String oldValue = getProperty(key);
    value = normalize( expandString(key, value));

    append0(key, oldValue, value, oldValue, separator);

    return oldValue;
  }

  protected String append (String key, String value, String separator) {
    String oldValue = getProperty(key);
    value = normalize( expandString(key, value));

    append0(key, oldValue, oldValue, value, separator);

    return oldValue;
  }


  private void append0 (String key, String oldValue, String a, String b, String separator){
    String newValue;

    if (a != null){
      if (b != null) {
        StringBuilder sb = new StringBuilder(a);
        if (separator != null) {
          sb.append(separator);
        }
        sb.append(b);
        newValue = sb.toString();

      } else { // b==null : nothing to append
        if (oldValue == a){ // using reference compare is intentional here
          return; // no change
        } else {
          newValue = a;
        }
      }

    } else { // a==null : nothing to append to
      if (oldValue == b || b == null){  // using reference compare is intentional here
        return; // no change
      } else {
        newValue = b;
      }
    }

    // if we get here, we have a newValue that differs from oldValue
    put0(key, newValue);
    notifyPropertyChangeListeners(key, oldValue, newValue);
  }

  protected String append (String key, String value) {
    return append(key, value, LIST_SEPARATOR); // append with our standard list separator
  }


  public void setClassLoader (ClassLoader newLoader){
    loader = newLoader;
  }

  public ClassLoader getClassLoader (){
    return loader;
  }

  public boolean hasSetClassLoader (){
    return Config.class.getClassLoader() != loader;
  }

  public JPFClassLoader initClassLoader( ClassLoader parent) {
    ArrayList<String> list = new ArrayList<String>();

    String[] cp = getCompactStringArray("native_classpath");
    cp = FileUtils.expandWildcards(cp);
    for (String e : cp) {
      list.add(e);
    }
    URL[] urls = FileUtils.getURLs(list);

    String[] nativeLibs = getCompactStringArray("native_libraries");

    //URLClassLoader cl = URLClassLoader.newInstance(urls, parent);
    JPFClassLoader cl = new JPFClassLoader( urls, nativeLibs, parent);

    //for (URL url : urls) System.out.println("@@ " + url);

    loader = cl;

    return cl;
  }


  //------------------------------ public methods - the Config API


  public String[] getEntrySequence () {
    // whoever gets this might add/append/remove items, so we have to
    // avoid ConcurrentModificationExceptions
    return entrySequence.toArray(new String[entrySequence.size()]);
  }

  public void addChangeListener (ConfigChangeListener l) {
    if (changeListeners == null) {
      changeListeners = new ArrayList<ConfigChangeListener>();
      changeListeners.add(l);
    } else {
      if (!changeListeners.contains(l)) {
        changeListeners.add(l);
      }
    }
  }
  
  public void removeChangeListener (ConfigChangeListener l) {
    if (changeListeners != null) {
      changeListeners.remove(l);
      
      if (changeListeners.size() == 0) {
        changeListeners = null;
      }
    }
  }
  
  
  public JPFException exception (String msg) {
    String context = getString("config");
    if (context != null){
      msg = "error in " + context + " : " + msg;
    }

    return new JPFConfigException(msg);
  }

  public void throwException(String msg) {
    throw new JPFConfigException(msg);
  }

  //------------------------ special properties
  public String getTarget() {
    return getString(TARGET_KEY);
  }

  public void setTarget (String target){
    setProperty(TARGET_KEY,target);
  }

  public String[] getTargetArgs() {
    String[] args = getStringArray(TARGET_ARGS_KEY);
    if (args == null){
      args = new String[0];
    }
    return args;
  }

  public void setTargetArgs (String... args) {
    StringBuilder sb = new StringBuilder();
    for (int i=0, n = 0; i < args.length; i++) {
      String a = args[i];
      if (a != null) {
        if (n++ > 0) {
          sb.append(LIST_SEPARATOR);
        }
        // we expand to be consistent with an explicit 'target_args' spec
        sb.append(expandString(null, a));
      }
    }
    if (sb.length() > 0) {
      setProperty(TARGET_ARGS_KEY, sb.toString());
    }
  }


  //----------------------- type specific accessors

  public boolean getBoolean(String key) {
    String v = getProperty(key);
    return (v == TRUE);
  }

  public boolean getBoolean(String key, boolean def) {
    String v = getProperty(key);
    if (v != null) {
      return (v == TRUE);
    } else {
      return def;
    }
  }

  /**
   * for a given <baseKey>, check if there are corresponding
   * values for keys <baseKey>.0 ... <baseKey>.<maxSize>
   * If a value is found, store it in an array at the respective index
   *
   * @param baseKey String with base key without trailing '.'
   * @param maxSize maximum size of returned value array
   * @return trimmed array with String values found in dictionary
   */
  public String[] getStringEnumeration (String baseKey, int maxSize) {
    String[] arr = new String[maxSize];
    int max=-1;

    StringBuilder sb = new StringBuilder(baseKey);
    sb.append('.');
    int len = baseKey.length()+1;

    for (int i=0; i<maxSize; i++) {
      sb.setLength(len);
      sb.append(i);

      String v = getString(sb.toString());
      if (v != null) {
        arr[i] = v;
        max = i;
      }
    }

    if (max >= 0) {
      max++;
      if (max < maxSize) {
        String[] a = new String[max];
        System.arraycopy(arr,0,a,0,max);
        return a;
      } else {
        return arr;
      }
    } else {
      return null;
    }
  }

  public String[] getKeysStartingWith (String prefix){
    ArrayList<String> list = new ArrayList<String>();

    for (Enumeration e = keys(); e.hasMoreElements(); ){
      String k = e.toString();
      if (k.startsWith(prefix)){
        list.add(k);
      }
    }

    return list.toArray(new String[list.size()]);
  }

  public int[] getIntArray (String key) throws JPFConfigException {
    String v = getProperty(key);

    if (v != null) {
      String[] sa = split(v);
      int[] a = new int[sa.length];
      int i = 0;
      try {
        for (; i<sa.length; i++) {
          a[i] = Integer.parseInt(sa[i]);
        }
        return a;
      } catch (NumberFormatException nfx) {
        throw new JPFConfigException("illegal int[] element in '" + key + "' = \"" + sa[i] + '"');
      }
    } else {
      return null;
    }
  }

  public long getDuration (String key, long defValue) {
    String v = getProperty(key);
    if (v != null) {
      long d = 0;

      if (v.indexOf(':') > 0){
        String[] a = v.split(":");
        if (a.length > 3){
          //log.severe("illegal duration: " + key + "=" + v);
          return defValue;
        }
        int m = 1000;
        for (int i=a.length-1; i>=0; i--, m*=60){
          try {
            int n = Integer.parseInt(a[i]);
            d += m*n;
          } catch (NumberFormatException nfx) {
            return defValue;
          }
        }

      } else {
        try {
          d = Long.parseLong(v);
        } catch (NumberFormatException nfx) {
          return defValue;
        }
      }

      return d;
    }

    return defValue;
  }

  public int getInt(String key) {
    return getInt(key, 0);
  }

  public int getInt(String key, int defValue) {
    String v = getProperty(key);
    if (v != null) {
      try {
        return Integer.parseInt(v);
      } catch (NumberFormatException nfx) {
        return defValue;
      }
    }

    return defValue;
  }

  public long getLong(String key) {
    return getLong(key, 0L);
  }

  public long getLong(String key, long defValue) {
    String v = getProperty(key);
    if (v != null) {
      try {
        return Long.parseLong(v);
      } catch (NumberFormatException nfx) {
        return defValue;
      }
    }

    return defValue;
  }

  public long[] getLongArray (String key) throws JPFConfigException {
    String v = getProperty(key);

    if (v != null) {
      String[] sa = split(v);
      long[] a = new long[sa.length];
      int i = 0;
      try {
        for (; i<sa.length; i++) {
          a[i] = Long.parseLong(sa[i]);
        }
        return a;
      } catch (NumberFormatException nfx) {
        throw new JPFConfigException("illegal long[] element in " + key + " = " + sa[i]);
      }
    } else {
      return null;
    }
  }


  public double getDouble (String key) {
    return getDouble(key, 0.0);
  }

  public double getDouble (String key, double defValue) {
    String v = getProperty(key);
    if (v != null) {
      try {
        return Double.parseDouble(v);
      } catch (NumberFormatException nfx) {
        return defValue;
      }
    }

    return defValue;
  }

  public double[] getDoubleArray (String key) throws JPFConfigException {
    String v = getProperty(key);

    if (v != null) {
      String[] sa = split(v);
      double[] a = new double[sa.length];
      int i = 0;
      try {
        for (; i<sa.length; i++) {
          a[i] = Double.parseDouble(sa[i]);
        }
        return a;
      } catch (NumberFormatException nfx) {
        throw new JPFConfigException("illegal double[] element in " + key + " = " + sa[i]);
      }
    } else {
      return null;
    }
  }

  public <T extends Enum<T>> T getEnum( String key, T[] values, T defValue){
    String v = getProperty(key);

    if (v != null){
      for (T t : values){
        if (v.equalsIgnoreCase(t.name())){
          return t;
        }
      }
    }

    return defValue;
  }

  public String getString(String key) {
    return getProperty(key);
  }

  public String getString(String key, String defValue) {
    String s = getProperty(key);
    if (s != null) {
      return s;
    } else {
      return defValue;
    }
  }

  /**
   * return memory size in bytes, or 'defValue' if not in dictionary. Encoding
   * can have a 'M' or 'k' postfix, values have to be positive integers (decimal
   * notation)
   */
  public long getMemorySize(String key, long defValue) {
    String v = getProperty(key);
    long sz = defValue;

    if (v != null) {
      int n = v.length() - 1;
      try {
        char c = v.charAt(n);

        if ((c == 'M') || (c == 'm')) {
          sz = Long.parseLong(v.substring(0, n)) << 20;
        } else if ((c == 'K') || (c == 'k')) {
          sz = Long.parseLong(v.substring(0, n)) << 10;
        } else {
          sz = Long.parseLong(v);
        }

      } catch (NumberFormatException nfx) {
        return defValue;
      }
    }

    return sz;
  }

  public HashSet<String> getStringSet(String key){
    String v = getProperty(key);
    if (v != null && (v.length() > 0)) {
      HashSet<String> hs = new HashSet<String>();
      for (String s : split(v)) {
        hs.add(s);
      }
      return hs;
    }

    return null;
    
  }
  
  public HashSet<String> getNonEmptyStringSet(String key){
    HashSet<String> hs = getStringSet(key);
    if (hs != null && hs.isEmpty()) {
      return null;
    } else {
      return hs;
    }
  }
    
  public String[] getStringArray(String key) {
    String v = getProperty(key);
    if (v != null && (v.length() > 0)) {
      return split(v);
    }

    return null;
  }

  public String[] getStringArray(String key, char[] delims) {
    String v = getProperty(key);
    if (v != null && (v.length() > 0)) {
      return split(v,delims);
    }

    return null;
  }

  public String[] getCompactTrimmedStringArray (String key){
    String[] a = getStringArray(key);

    if (a != null) {
      for (int i = 0; i < a.length; i++) {
        String s = a[i];
        if (s != null && s.length() > 0) {
          a[i] = s.trim();
        }
      }

      return removeEmptyStrings(a);

    } else {
      return EMPTY_STRING_ARRAY;
    }
  }

  public String[] getCompactStringArray(String key){
    return removeEmptyStrings(getStringArray(key));
  }

  
  public String[] getStringArray(String key, String[] def){
    String v = getProperty(key);
    if (v != null && (v.length() > 0)) {
      return split(v);
    } else {
      return def;
    }
  }

  public static String[] removeEmptyStrings (String[] a){
    if (a != null) {
      int n = 0;
      for (int i=0; i<a.length; i++){
        if (a[i].length() > 0){
          n++;
        }
      }

      if (n < a.length){ // we have empty strings in the split
        String[] r = new String[n];
        for (int i=0, j=0; i<a.length; i++){
          if (a[i].length() > 0){
            r[j++] = a[i];
            if (j == n){
              break;
            }
          }
        }
        return r;

      } else {
        return a;
      }
    }

    return null;
  }


  /**
   * return an [optional] id part of a property value (all that follows the first '@')
   */
  String getIdPart (String key) {
    String v = getProperty(key);
    if ((v != null) && (v.length() > 0)) {
      int i = v.indexOf('@');
      if (i >= 0){
        return v.substring(i+1);
      }
    }

    return null;
  }

  public Class<?> asClass (String v) throws JPFConfigException {
    if ((v != null) && (v.length() > 0)) {
      v = stripId(v);
      v = expandClassName(v);
      try {
        return loader.loadClass(v);
      } catch (ClassNotFoundException cfx) {
        throw new JPFConfigException("class not found " + v + " by classloader: " + loader);
      } catch (ExceptionInInitializerError ix) {
        throw new JPFConfigException("class initialization of " + v + " failed: " + ix,
            ix);
      }
    }

    return null;    
  }
      
  public <T> Class<? extends T> getClass(String key, Class<T> type) throws JPFConfigException {
    Class<?> cls = asClass( getProperty(key));
    if (cls != null) {
      if (type.isAssignableFrom(cls)) {
        return cls.asSubclass(type);
      } else {
        throw new JPFConfigException("classname entry for: \"" + key + "\" not of type: " + type.getName());
      }
    }
    return null;
  }
  
    
  public Class<?> getClass(String key) throws JPFConfigException {
    return asClass( getProperty(key));
  }
  
  public Class<?> getEssentialClass(String key) throws JPFConfigException {
    Class<?> cls = getClass(key);
    if (cls == null) {
      throw new JPFConfigException("no classname entry for: \"" + key + "\"");
    }

    return cls;
  }
  
  String stripId (String v) {
    int i = v.indexOf('@');
    if (i >= 0) {
      return v.substring(0,i);
    } else {
      return v;
    }
  }

  String getId (String v){
    int i = v.indexOf('@');
    if (i >= 0) {
      return v.substring(i+1);
    } else {
      return null;
    }
  }

  String expandClassName (String clsName) {
    if (clsName != null && clsName.length() > 0 && clsName.charAt(0) == '.') {
      return "gov.nasa.jpf" + clsName;
    } else {
      return clsName;
    }
  }

  
  public Class<?>[] getClasses(String key) throws JPFConfigException {
    String[] v = getStringArray(key);
    if (v != null) {
      int n = v.length;
      Class<?>[] a = new Class[n];
      for (int i = 0; i < n; i++) {
        String clsName = expandClassName(v[i]);
        if (clsName != null && clsName.length() > 0){
          try {
            clsName = stripId(clsName);
            a[i] = loader.loadClass(clsName);
          } catch (ClassNotFoundException cnfx) {
            throw new JPFConfigException("class not found " + v[i]);
          } catch (ExceptionInInitializerError ix) {
            throw new JPFConfigException("class initialization of " + v[i] + " failed: " + ix, ix);
          }
        }
      }

      return a;
    }

    return null;
  }
  
  // <2do> - that's kind of kludged together, not very efficient
  String[] getIds (String key) {
    String v = getProperty(key);

    if (v != null) {
      int i = v.indexOf('@');
      if (i >= 0) { // Ok, we have ids
        String[] a = split(v);
        String[] ids = new String[a.length];
        for (i = 0; i<a.length; i++) {
          ids[i] = getId(a[i]);
        }
        return ids;
      }
    }

    return null;
  }

  public <T> ArrayList<T> getInstances(String key, Class<T> type) throws JPFConfigException {

    Class<?>[] argTypes = { Config.class };
    Object[] args = { this };

    return getInstances(key,type,argTypes,args);
  }
  
  public <T> ArrayList<T> getInstances(String key, Class<T> type, Class<?>[]argTypes, Object[] args)
                                                      throws JPFConfigException {
    Class<?>[] c = getClasses(key);

    if (c != null) {
      String[] ids = getIds(key);

      ArrayList<T> a = new ArrayList<T>(c.length);

      for (int i = 0; i < c.length; i++) {
        String id = (ids != null) ? ids[i] : null;
        T listener = getInstance(key, c[i], type, argTypes, args, id);
        if (listener != null) {
          a.add( listener);
        } else {
          // should report here
        }
      }

      return a;
      
    } else {
      // should report here
    }

    return null;
  }
  
  public <T> T getInstance(String key, Class<T> type, String defClsName) throws JPFConfigException {
    Class<?>[] argTypes = CONFIG_ARGTYPES;
    Object[] args = CONFIG_ARGS;

    Class<?> cls = getClass(key);
    String id = getIdPart(key);

    if (cls == null) {
      try {
        cls = loader.loadClass(defClsName);
      } catch (ClassNotFoundException cfx) {
        throw new JPFConfigException("class not found " + defClsName);
      } catch (ExceptionInInitializerError ix) {
        throw new JPFConfigException("class initialization of " + defClsName + " failed: " + ix, ix);
      }
    }
    
    return getInstance(key, cls, type, argTypes, args, id);
  }

  public <T> T getInstance(String key, Class<T> type) throws JPFConfigException {
    Class<?>[] argTypes = CONFIG_ARGTYPES;
    Object[] args = CONFIG_ARGS;

    return getInstance(key, type, argTypes, args);
  }
    
  public <T> T getInstance(String key, Class<T> type, Class<?>[] argTypes,
                            Object[] args) throws JPFConfigException {
    Class<?> cls = getClass(key);
    String id = getIdPart(key);

    if (cls != null) {
      return getInstance(key, cls, type, argTypes, args, id);
    } else {
      return null;
    }
  }
  
  public <T> T getInstance(String key, Class<T> type, Object arg1, Object arg2)  throws JPFConfigException {
    Class<?>[] argTypes = new Class<?>[2];
    argTypes[0] = arg1.getClass();
    argTypes[1] = arg2.getClass();

    Object[] args = new Object[2];
    args[0] = arg1;
    args[1] = arg2;

    return getInstance(key, type, argTypes, args);
  }


  public <T> T getEssentialInstance(String key, Class<T> type) throws JPFConfigException {
    Class<?>[] argTypes = { Config.class };
    Object[] args = { this };
    return getEssentialInstance(key, type, argTypes, args);
  }

  /**
   * just a convenience method for ctor calls that take two arguments
   */
  public <T> T getEssentialInstance(String key, Class<T> type, Object arg1, Object arg2)  throws JPFConfigException {
    Class<?>[] argTypes = new Class<?>[2];
    argTypes[0] = arg1.getClass();
    argTypes[1] = arg2.getClass();

    Object[] args = new Object[2];
    args[0] = arg1;
    args[1] = arg2;

    return getEssentialInstance(key, type, argTypes, args);
  }

  public <T> T getEssentialInstance(String key, Class<T> type, Class<?>[] argTypes, Object[] args) throws JPFConfigException {
    Class<?> cls = getEssentialClass(key);
    String id = getIdPart(key);

    return getInstance(key, cls, type, argTypes, args, id);
  }

  public <T> T getInstance (String id, String clsName, Class<T> type, Class<?>[] argTypes, Object[] args) throws JPFConfigException {
    Class<?> cls = asClass(clsName);
    
    if (cls != null) {
      return getInstance(id, cls, type, argTypes, args, id);
    } else {
      return null;
    }
  }
  
  public <T> T getInstance (String id, String clsName, Class<T> type) throws JPFConfigException {
    Class<?>[] argTypes = CONFIG_ARGTYPES;
    Object[] args = CONFIG_ARGS;

    Class<?> cls = asClass(clsName);
    
    if (cls != null) {
      return getInstance(id, cls, type, argTypes, args, id);
    } else {
      return null;
    }
  }
  
  /**
   * this is our private instantiation workhorse - try to instantiate an object of
   * class 'cls' by using the following ordered set of ctors 1. <cls>(
   * <argTypes>) 2. <cls>(Config) 3. <cls>() if all of that fails, or there was
   * a 'type' provided the instantiated object does not comply with, return null
   */
  <T> T getInstance(String key, Class<?> cls, Class<T> type, Class<?>[] argTypes,
                     Object[] args, String id) throws JPFConfigException {
    Object o = null;
    Constructor<?> ctor = null;

    if (cls == null) {
      return null;
    }

    if (id != null) { // check first if we already have this one instantiated as a singleton
      if (singletons == null) {
        singletons = new HashMap<String,Object>();
      } else {
        o = type.cast(singletons.get(id));
      }
    }

    while (o == null) {
      try {
        ctor = cls.getConstructor(argTypes);
        o = ctor.newInstance(args);
      } catch (NoSuchMethodException nmx) {
         
        if ((argTypes.length > 1) || ((argTypes.length == 1) && (argTypes[0] != Config.class))) {
          // fallback 1: try a single Config param
          argTypes = CONFIG_ARGTYPES;
          args = CONFIG_ARGS;

        } else if (argTypes.length > 0) {
          // fallback 2: try the default ctor
          argTypes = NO_ARGTYPES;
          args = NO_ARGS;

        } else {
          // Ok, there is no suitable ctor, bail out
          throw new JPFConfigException(key, cls, "no suitable ctor found");
        }
      } catch (IllegalAccessException iacc) {
        throw new JPFConfigException(key, cls, "\n> ctor not accessible: "
            + getMethodSignature(ctor));
      } catch (IllegalArgumentException iarg) {
        throw new JPFConfigException(key, cls, "\n> illegal constructor arguments: "
            + getMethodSignature(ctor));
      } catch (InvocationTargetException ix) {
        Throwable tx = ix.getTargetException();
        if (tx instanceof JPFConfigException) {
          throw new JPFConfigException(tx.getMessage() + "\n> used within \"" + key
              + "\" instantiation of " + cls);
        } else {
          throw new JPFConfigException(key, cls, "\n> exception in "
              + getMethodSignature(ctor) + ":\n>> " + tx, tx);
        }
      } catch (InstantiationException ivt) {
        throw new JPFConfigException(key, cls,
            "\n> abstract class cannot be instantiated");
      } catch (ExceptionInInitializerError eie) {
        throw new JPFConfigException(key, cls, "\n> static initialization failed:\n>> "
            + eie.getException(), eie.getException());
      }
    }

    // check type
    if (!type.isInstance(o)) {
      throw new JPFConfigException(key, cls, "\n> instance not of type: "
          + type.getName());
    }

    if (id != null) { // add to singletons (in case it's not already in there)
      singletons.put(id, o);
    }

    return type.cast(o); // safe according to above
  }

  String getMethodSignature(Constructor<?> ctor) {
    StringBuilder sb = new StringBuilder(ctor.getName());
    sb.append('(');
    Class<?>[] argTypes = ctor.getParameterTypes();
    for (int i = 0; i < argTypes.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(argTypes[i].getName());
    }
    sb.append(')');
    return sb.toString();
  }

  public boolean hasValue(String key) {
    String v = getProperty(key);
    return ((v != null) && (v.length() > 0));
  }

  public boolean hasValueIgnoreCase(String key, String value) {
    String v = getProperty(key);
    if (v != null) {
      return v.equalsIgnoreCase(value);
    }

    return false;
  }

  public int getChoiceIndexIgnoreCase(String key, String[] choices) {
    String v = getProperty(key);

    if ((v != null) && (choices != null)) {
      for (int i = 0; i < choices.length; i++) {
        if (v.equalsIgnoreCase(choices[i])) {
          return i;
        }
      }
    }

    return -1;
  }

  public URL getURL (String key){
    String v = getProperty(key);
    if (v != null) {
      try {
        return FileUtils.getURL(v);
      } catch (Throwable x){
        throw exception("malformed URL: " + v);
      }
    } else {
      return null;
    }
  }

  public File[] getPathArray (String key) {    
    String v = getProperty(key);
    if (v != null) {
      String[] pe = removeEmptyStrings( pathSplit(v));
      
      if (pe != null && pe.length > 0) {
        File[] files = new File[pe.length];
        for (int i=0; i<files.length; i++) {
          String path = FileUtils.asPlatformPath(pe[i]);
          files[i] = new File(path);
        }
        return files;
      }      
    }

    return new File[0];
  }

  public File getPath (String key) {
    String v = getProperty(key);
    if (v != null) {
      return new File(FileUtils.asPlatformPath(v));
    }
    
    return null;
  }

  static final char[] UNIX_PATH_SEPARATORS = {',', ';', ':' };
  static final char[] WINDOWS_PATH_SEPARATORS = {',', ';' };

  protected String[] pathSplit (String input){
    if (File.pathSeparatorChar == ':'){
      return split( input, UNIX_PATH_SEPARATORS);
    } else {
      return split( input, WINDOWS_PATH_SEPARATORS);
    }
  }

  static final char[] DELIMS = { ',', ';' };

  /**
   * our own version of split, which handles "`" quoting, and breaks on non-quoted
   * ',' and ';' chars. We need this so that we can use ';' separated lists in
   * JPF property files, but still can use quoted ';' if we absolutely have to
   * specify Java signatures. On the other hand, we can't quote with '\' because
   * that would make Windows paths even more terrible.
   * regexes are bad at quoting, and this is more efficient anyways
   */
  protected String[] split (String input){
    return split(input, DELIMS);
  }

  private boolean isDelim(char[] delim, char c){
    for (int i=0; i<delim.length; i++){
      if (c == delim[i]){
        return true;
      }
    }
    return false;
  }

  protected String[] split (String input, char[] delim){
    int n = input.length();
    ArrayList<String> elements = new ArrayList<String>();
    boolean quote = false;

    char[] buf = new char[128];
    int k=0;

    for (int i=0; i<n; i++){
      char c = input.charAt(i);

      if (!quote) {
        if (isDelim(delim,c)){ // element separator
          elements.add( new String(buf, 0, k));
          k = 0;
          continue;
        } else if (c=='`') {
          quote = true;
          continue;
        }
      }

      if (k >= buf.length){
        char[] newBuf = new char[buf.length+128];
        System.arraycopy(buf, 0, newBuf, 0, k);
        buf = newBuf;
      }
      buf[k++] = c;
      quote = false;
    }

    if (k>0){
      elements.add( new String(buf, 0, k));
    }

    return elements.toArray(new String[elements.size()]);
  }


  /**
   * collect all the <project>.{native_classpath,classpath,sourcepath,peer_packages,native_libraries}
   * and append them to the global settings
   */
  void collectGlobalPaths() {
    // note - this is in the order of entry, i.e. reflects priorities
    // we have to process this in reverse order so that later entries are prioritized
    String[] keys = getEntrySequence();

    String nativeLibKey = "." + System.getProperty("os.name") +
            '.' + System.getProperty("os.arch") + ".native_libraries";

    for (int i = keys.length-1; i>=0; i--){
      String k = keys[i];
      if (k.endsWith(".native_classpath")){
        appendPath("native_classpath", k);
      } else if (k.endsWith(".classpath")){
        appendPath("classpath", k);
      } else if (k.endsWith(".sourcepath")){
        appendPath("sourcepath", k);
      } else if (k.endsWith("peer_packages")){
        append("peer_packages", getString(k), ",");
      } else if (k.endsWith(nativeLibKey)){
        appendPath("native_libraries", k);
      }
    }
  }

  static Pattern absPath = Pattern.compile("(?:[a-zA-Z]:)?[/\\\\].*");

  void appendPath (String pathKey, String key){
    String projName = key.substring(0, key.indexOf('.'));
    String pathPrefix = null;

    if (projName.isEmpty()){
      pathPrefix = new File(".").getAbsolutePath();
    } else {
      pathPrefix = getString(projName);
    }

    if (pathPrefix != null){
      pathPrefix += '/';

      String[] elements = getCompactStringArray(key);
      if (elements != null){
        for (String e : elements) {
          if (e != null && e.length()>0){

            // if this entry is not an absolute path, or doesn't start with
            // the project path, prepend the project path
            if (!(absPath.matcher(e).matches()) && !e.startsWith(pathPrefix)) {
              e = pathPrefix + e;
            }

            append(pathKey, e);
          }
        }
      }

    } else {
      throw new JPFConfigException("no project path for " + key);
    }
  }


  //--- our modification interface

  /**
   * iterate over all keys, if a key starts with the provided keyPrefix, add
   * this value under the corresponding key suffix. For example:
   *
   *  test.report.console.finished = result
   *
   *    -> prompotePropertyCategory("test.") ->
   *
   *  report.console.finished = result
   *
   * if a matching key has an IGNORE_VALUE value ("-"), the entry is *not* promoted
   * (we need this to override promoted keys)
   */
  public void promotePropertyCategory (String keyPrefix){
    int prefixLen = keyPrefix.length();

    for (Map.Entry<Object,Object> e : entrySet()){
      Object k = e.getKey();
      if (k instanceof String){
        String key = (String)k;
        if (key.startsWith(keyPrefix)){
          Object v = e.getValue();
          if (! IGNORE_VALUE.equals(v)){
            String keySuffix = key.substring(prefixLen);
            put(keySuffix, v);
          }
        }
      }
    }
  }

  
  @Override
  public Object setProperty (String key, String newValue) {    
    Object oldValue = put(key, newValue);    
    notifyPropertyChangeListeners(key, (String)oldValue, newValue);
    return oldValue;
  }

  public void parse (String s) {
    
    int i = s.indexOf("=");
    if (i > 0) {
      String key, val;
      
      if (i > 1 && s.charAt(i-1)=='+') { // append
        key = s.substring(0, i-1).trim();
        val = s.substring(i+1); // it's going to be normalized anyways
        append(key, val);
        
      } else { // put
        key = s.substring(0, i).trim();
        val = s.substring(i+1);
        setProperty(key, val);
      }
      
    }
  }
  
  protected void notifyPropertyChangeListeners (String key, String oldValue, String newValue) {
    if (changeListeners != null) {
      for (ConfigChangeListener l : changeListeners) {
        l.propertyChanged(this, key, oldValue, newValue);
      }
    }    
  }
  
  public String[] asStringArray (String s){
    return split(s);
  }
  
  public TreeMap<Object,Object> asOrderedMap() {
    TreeMap<Object,Object> map = new TreeMap<Object,Object>();
    map.putAll(this);
    return map;
  }

  public void print (PrintWriter pw) {
    pw.println("----------- Config contents");

    // just how much do you have to do to get a printout with keys in alphabetical order :<
    TreeSet<String> kset = new TreeSet<String>();
    for (Enumeration<?> e = propertyNames(); e.hasMoreElements();) {
      Object k = e.nextElement();
      if (k instanceof String) {
        kset.add( (String)k);
      }
    }

    for (String key : kset) {
      String val = getProperty(key);
      pw.print(key);
      pw.print(" = ");
      pw.println(val);
    }

    pw.flush();
  }

  /*
   * for debugging purposes
   */
  public void printEntries() {
    PrintWriter pw = new PrintWriter(System.out);
    print(pw);
  }

  public String getSourceName (Object src){
    if (src instanceof File){
      return ((File)src).getAbsolutePath();
    } else if (src instanceof URL){
      return ((URL)src).toString();
    } else {
      return src.toString();
    }
  }
  
  public List<Object> getSources() {
    return sources;
  }
  
  public void printStatus(Logger log) {
    int idx = 0;
    
    for (Object src : sources){
      if (src instanceof File){
        log.config("configuration source " + idx++ + " : " + getSourceName(src));
      }
    }
  }


}

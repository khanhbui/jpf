package gov.nasa.jpf;

import gov.nasa.jpf.util.test.TestJPF;
import java.io.*;
import java.util.regex.*;
import org.junit.Test;


/**
 * unit test for Config
 */
public class ConfigTest extends TestJPF {

  public static void main (String[] args){
    runTestsOfThisClass(args);
  }

  @Test
  public void testDefaultAppPropertyInit () {

    String dir = "src/tests/gov/nasa/jpf";
    String[] args = {dir + "/configTestApp.jpf"};

    Config conf = new Config(args);

    String val = conf.getString("vm.class");
    assert "gov.nasa.jpf.jvm.JVM".equals(val);

    val = conf.getTarget(); // from configTest.jpf
    assert "urgh.org.MySystemUnderTest".equals(val);

    // that's testing key expansion and the builtin "config_path"
    val = conf.getString("mySUT.location");
    
    assert val != null;
    
    if (!File.separator.equals("/"))
       dir = dir.replaceAll("/", Matcher.quoteReplacement(File.separator));  // On UNIX Config returns / and on Windows Config returns \\

    assert val.endsWith(dir);
  }

  @Test
  public void testDefaultExplicitTargetInit ()  {
    String[] args = {"urgh.org.MySystemUnderTest"};

    Config conf = new Config( args);
    assert "urgh.org.MySystemUnderTest".equals(conf.getTarget());
  }

  @Test
  public void testExplicitLocations () {
    String dir = "src/tests/gov/nasa/jpf/";
    String[] args = {"+site=" + dir + "configTestSite.properties",
                     "+app=" + dir + "configTestApp.jpf" };

    Config conf = new Config( args);
    conf.printEntries();

    assert "urgh.org.MySystemUnderTest".equals(conf.getTarget());
  }

  @Test
  public void testTargetArgsOverride () {

    String dir = "src/tests/gov/nasa/jpf/";
    String[] args = { dir + "configTestApp.jpf",
                      "x", "y"};

    Config conf = new Config(args);
    conf.printEntries();

    String[] ta = conf.getTargetArgs();
    assert ta.length == 2;
    assert "x".equals(ta[0]);
    assert "y".equals(ta[1]);
  }

  @Test
  public void testClassPaths () {
    String dir = "src/tests/gov/nasa/jpf/";
    String[] args = {"+site=" + dir + "configTestSite.properties",
                     "+app=" + dir + "configTestApp.jpf" };

    Config conf = new Config( args);
    conf.printEntries();

    // those properties are very weak!
    String[] bootCpEntries = conf.asStringArray("boot_classpath");
    assert bootCpEntries.length > 0;

    String[] nativeCpEntries = conf.asStringArray("native_classpath");
    assert nativeCpEntries.length > 0;
  }

  @Test
  public void testRequiresOk () {
    String dir = "src/tests/gov/nasa/jpf/";
    String[] args = { "+site=" + dir + "configTestSite.properties",
                      dir + "configTestRequires.jpf" };

    Config.enableLogging(true);
    Config conf = new Config( args);
    String v = conf.getString("whoa");
    System.out.println("got whoa = " + v);
    
    assert (v != null) && v.equals("boa");
  }

  @Test
  public void testRequiresFail () {
    String dir = "src/tests/gov/nasa/jpf/";
    String[] args = { "+site=" + dir + "configTestSite.properties",
                      dir + "configTestRequiresFail.jpf" };

    Config.enableLogging(true);
    Config conf = new Config( args);
    String v = conf.getString("whoa");
    System.out.println("got whoa = " + v);

    assert (v == null);
  }

  @Test
  public void testIncludes () {
    String dir = "src/tests/gov/nasa/jpf/";
    String[] args = { "+site=" + dir + "configTestSite.properties",
                      dir + "configTestIncludes.jpf" };

    Config.enableLogging(true);
    Config conf = new Config( args);
    String v = conf.getString("my.common");
    System.out.println("got my.common = " + v);

    assert (v != null) && v.equals("whatever");
  }

}

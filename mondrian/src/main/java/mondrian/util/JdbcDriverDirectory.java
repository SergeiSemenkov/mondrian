/*
 * This software is subject to the terms of the Eclipse Public License v1.0
 * Agreement, available at the following URL:
 * http://www.eclipse.org/legal/epl-v10.html.
 * You must accept the terms of that agreement to use this software.
 */
package mondrian.util;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * JDBC drivers loaded from a directory at run time, beside the ones in {@code WEB-INF/lib}: the
 * webapp's {@code drivers/} (a volume, so an installed driver survives a new image), or
 * {@code emondrian.drivers.dir}. Read at startup by {@code MondrianModuleServletRegistrar}, and
 * one jar at a time by the MCP tool {@code install_driver}, without a restart.
 *
 * <p>Each jar gets its own class loader, a child of the webapp's, and its {@code java.sql.Driver}
 * services are registered with {@link DriverManager} through a {@link Shim}. The shim is what makes
 * it work: {@code DriverManager.getConnection} ignores a driver whose class the caller's loader
 * cannot see, and the callers here (the engine, its connection pool) live in the webapp loader,
 * which cannot see a child's classes. It can see this class, so a shim around the driver passes.
 */
public final class JdbcDriverDirectory {

  private static final Logger LOGGER = LogManager.getLogger( JdbcDriverDirectory.class );

  public static final String DIRECTORY_PROPERTY = "emondrian.drivers.dir";

  /** The directory, set at startup from the webapp's {@code drivers/} unless the property names one. */
  private static volatile String directory;

  /** Canonical jar path to the driver classes registered from it. */
  private static final Map<String, List<String>> LOADED = new LinkedHashMap<>();

  private static final Set<String> CLASSES = ConcurrentHashMap.newKeySet();

  private JdbcDriverDirectory() {
  }

  /** The drivers directory: {@code emondrian.drivers.dir}, else the webapp's {@code drivers/}; null when unknown. */
  public static String directory() {
    String property = System.getProperty( DIRECTORY_PROPERTY );
    return property != null && !property.isBlank() ? property.trim() : directory;
  }

  /** Sets the default directory; called at startup. */
  public static void setDefaultDirectory( String path ) {
    directory = path;
  }

  /** Loads every {@code .jar} of the directory; a jar that fails is logged and skipped. */
  public static synchronized void loadAll() {
    String path = directory();
    if ( path == null ) {
      return;
    }
    File[] jars = new File( path ).listFiles( ( d, n ) -> n.endsWith( ".jar" ) );
    if ( jars == null ) {
      return;
    }
    java.util.Arrays.sort( jars );
    for ( File jar : jars ) {
      try {
        List<String> classes = load( jar );
        LOGGER.info( "JDBC drivers from " + jar.getName() + ": " + classes );
      } catch ( Exception e ) {
        LOGGER.warn( "JDBC driver jar " + jar.getName() + " not loaded: " + e.getMessage() );
      }
    }
  }

  /**
   * Registers the drivers of one jar and returns their class names. A jar loaded before returns
   * what it registered then. A jar that declares no {@code java.sql.Driver} service fails.
   */
  public static synchronized List<String> load( File jar ) throws IOException, SQLException {
    String key = jar.getCanonicalPath();
    List<String> known = LOADED.get( key );
    if ( known != null ) {
      return known;
    }
    URLClassLoader loader = new URLClassLoader(
      new URL[] { jar.toURI().toURL() }, JdbcDriverDirectory.class.getClassLoader() );
    List<String> classes = new ArrayList<>();
    try {
      for ( ServiceLoader.Provider<Driver> provider : (Iterable<ServiceLoader.Provider<Driver>>)
        ServiceLoader.load( Driver.class, loader ).stream()::iterator ) {
        // The parent's drivers are visible through this loader too; only this jar's own count.
        if ( provider.type().getClassLoader() != loader ) {
          continue;
        }
        Driver driver = provider.get();
        DriverManager.registerDriver( new Shim( driver ) );
        classes.add( driver.getClass().getName() );
      }
    } catch ( java.util.ServiceConfigurationError e ) {
      loader.close();
      throw new SQLException( "The jar's java.sql.Driver service cannot be loaded: " + e.getMessage(), e );
    }
    if ( classes.isEmpty() ) {
      loader.close();
      throw new SQLException( jar.getName() + " declares no JDBC driver of its own"
        + " (META-INF/services/java.sql.Driver)" );
    }
    List<String> result = Collections.unmodifiableList( classes );
    LOADED.put( key, result );
    CLASSES.addAll( classes );
    return result;
  }

  /** Jar path to driver classes, for every jar loaded so far. */
  public static synchronized Map<String, List<String>> loaded() {
    return new LinkedHashMap<>( LOADED );
  }

  /** Whether a driver class was registered from the directory: {@code JdbcDrivers=} naming it is then fine. */
  public static boolean isLoaded( String className ) {
    return CLASSES.contains( className );
  }

  /** A driver of a child loader, seen by {@link DriverManager} as a class of the webapp loader. */
  static final class Shim implements Driver {
    private final Driver driver;

    Shim( Driver driver ) {
      this.driver = driver;
    }

    @Override
    public Connection connect( String url, Properties info ) throws SQLException {
      return driver.connect( url, info );
    }

    @Override
    public boolean acceptsURL( String url ) throws SQLException {
      return driver.acceptsURL( url );
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo( String url, Properties info ) throws SQLException {
      return driver.getPropertyInfo( url, info );
    }

    @Override
    public int getMajorVersion() {
      return driver.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
      return driver.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
      return driver.jdbcCompliant();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return driver.getParentLogger();
    }

    @Override
    public String toString() {
      return "JdbcDriverDirectory.Shim(" + driver.getClass().getName() + ")";
    }
  }
}

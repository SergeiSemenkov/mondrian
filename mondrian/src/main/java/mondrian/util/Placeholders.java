/*
 * This software is subject to the terms of the Eclipse Public License v1.0
 * Agreement, available at the following URL:
 * http://www.eclipse.org/legal/epl-v10.html.
 * You must accept the terms of that agreement to use this software.
 */
package mondrian.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholders in connection properties, resolved only when a connection is opened:
 * <ul>
 *   <li>{@code ${env:NAME}} -- the environment variable {@code NAME};
 *   <li>{@code ${name}} -- the Java system property {@code name}.
 * </ul>
 *
 * <p>So {@code datasources.xml} can say {@code JdbcPassword=${env:EMONDRIAN_CH_PASSWORD}} and keep it
 * that way: the file, every client that reads it (the Catalog Editor, MCP) and every save keep
 * the placeholder, and only the JDBC driver sees the value. The file used to be substituted when
 * it was read, from system properties only, which handed the values to anyone allowed to read the
 * registry, wrote them back into the file on the next save, and needed them on the command line,
 * where Tomcat logs them.
 *
 * <p>Only names meant for this are resolved: environment variables starting {@code EMONDRIAN_},
 * system properties starting {@code emondrian.}, and any name listed in the system property
 * {@code emondrian.placeholders.allow} (comma-separated). Whoever can register a catalog writes
 * its connect string, and could otherwise send any secret in the server's environment to a host
 * of their choosing inside a JDBC URL.
 *
 * <p>A value is inserted literally ({@code $} and {@code \} included). A placeholder that is not
 * allowed or has no value stays as written and is logged once by name, never with a value.
 */
public final class Placeholders {

  private static final Logger LOGGER = LogManager.getLogger( Placeholders.class );
  private static final Pattern PLACEHOLDER = Pattern.compile( "\\$\\{([^${}]+)\\}" );
  private static final String ENV_PREFIX = "env:";
  private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

  private Placeholders() {
  }

  /** {@code text} with its placeholders resolved from the environment and system properties. */
  public static String resolve( String text ) {
    return resolve( text, System::getenv, System::getProperty );
  }

  static String resolve(
    String text, Function<String, String> env, Function<String, String> properties ) {
    Set<String> allowed = new java.util.HashSet<>();
    String list = properties.apply( "emondrian.placeholders.allow" );
    if ( list != null ) {
      for ( String name : list.split( "," ) ) {
        if ( !name.isBlank() ) {
          allowed.add( name.trim() );
        }
      }
    }
    return resolve( text, env, properties, allowed );
  }

  private static String resolve(
    String text, Function<String, String> env, Function<String, String> properties,
    Set<String> allowed ) {
    if ( text == null || text.indexOf( "${" ) < 0 ) {
      return text;
    }
    Matcher matcher = PLACEHOLDER.matcher( text );
    StringBuilder out = new StringBuilder( text.length() + 32 );
    while ( matcher.find() ) {
      String name = matcher.group( 1 ).trim();
      boolean isEnv = name.startsWith( ENV_PREFIX );
      String key = isEnv ? name.substring( ENV_PREFIX.length() ).trim() : name;
      boolean permitted = allowed.contains( key )
        || ( isEnv ? key.startsWith( "EMONDRIAN_" ) : key.startsWith( "emondrian." ) );
      String value = !permitted ? null : isEnv ? env.apply( key ) : properties.apply( key );
      if ( value == null ) {
        if ( WARNED.add( name ) ) {
          LOGGER.warn( "Connection placeholder ${" + name + "} "
            + ( !permitted
              ? "is not resolved: only EMONDRIAN_* environment variables, emondrian.* system properties"
                + " and names listed in emondrian.placeholders.allow are"
              : isEnv ? "has no value (environment variable not set)"
                : "has no value (system property not set)" )
            + "; it is passed on as written" );
        }
        value = matcher.group();
      }
      matcher.appendReplacement( out, Matcher.quoteReplacement( value ) );
    }
    matcher.appendTail( out );
    return out.toString();
  }
}

/*
 * This software is subject to the terms of the Eclipse Public License v1.0
 * Agreement, available at the following URL:
 * http://www.eclipse.org/legal/epl-v10.html.
 * You must accept the terms of that agreement to use this software.
 */
package mondrian.trace;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The client address a trace may record: resolved through trusted proxies, then cut to its
 * network. The same rules as the MCP traffic log ({@code emondrian.mcp.McpAudit}), and the same
 * settings, so one deployment configures both once:
 * <ul>
 *   <li>{@code emondrian.mcp.audit.trustedProxies} -- CIDRs whose {@code X-Forwarded-For} and
 *       {@code X-Real-IP} are believed; default loopback. The hops are XFF then X-Real-IP, walked
 *       from the right to the first untrusted one, so a forged header changes nothing.
 *   <li>{@code emondrian.mcp.audit.truncateIp} -- default {@code true}: IPv4 to /24, IPv6 to /48.
 * </ul>
 */
public final class ClientAddress {

  private static final List<byte[][]> TRUSTED = parse(
    System.getProperty( "emondrian.mcp.audit.trustedProxies", "127.0.0.0/8,::1/128" ) );
  private static final boolean TRUNCATE = !"false".equalsIgnoreCase(
    System.getProperty( "emondrian.mcp.audit.truncateIp", "true" ).trim() );

  /** The request origin the XML/A servlet saw, for the span the handler starts. */
  private static final ThreadLocal<String[]> ORIGIN = new ThreadLocal<>();

  private ClientAddress() {
  }

  /** Records the current request's client address (already resolved and cut) and user agent. */
  public static void setOrigin( String remoteAddr, String forwardedFor, String realIp, String userAgent ) {
    ORIGIN.set( new String[] { logged( resolve( remoteAddr, forwardedFor, realIp ) ), userAgent } );
  }

  public static void clearOrigin() {
    ORIGIN.remove();
  }

  /** {address, userAgent} of the current request, or null. */
  public static String[] origin() {
    return ORIGIN.get();
  }

  static String resolve( String remoteAddr, String forwardedFor, String realIp ) {
    if ( remoteAddr == null || !trusted( remoteAddr ) ) {
      return remoteAddr;
    }
    List<String> hops = new ArrayList<>();
    if ( forwardedFor != null ) {
      for ( String hop : forwardedFor.split( "," ) ) {
        if ( !hop.isBlank() ) {
          hops.add( hop.trim() );
        }
      }
    }
    if ( realIp != null && !realIp.isBlank() ) {
      hops.add( realIp.trim() );
    }
    String leftmost = remoteAddr;
    for ( int i = hops.size() - 1; i >= 0; i-- ) {
      leftmost = hops.get( i );
      if ( !trusted( leftmost ) ) {
        return leftmost;
      }
    }
    return leftmost;
  }

  static String logged( String address ) {
    if ( address == null || !TRUNCATE ) {
      return address;
    }
    byte[] b = literal( address );
    if ( b == null ) {
      return "invalid";
    }
    if ( b.length == 4 ) {
      return ( b[ 0 ] & 0xff ) + "." + ( b[ 1 ] & 0xff ) + "." + ( b[ 2 ] & 0xff ) + ".0";
    }
    return String.format( "%x:%x:%x::", ( ( b[ 0 ] & 0xff ) << 8 ) | ( b[ 1 ] & 0xff ),
      ( ( b[ 2 ] & 0xff ) << 8 ) | ( b[ 3 ] & 0xff ), ( ( b[ 4 ] & 0xff ) << 8 ) | ( b[ 5 ] & 0xff ) );
  }

  private static boolean trusted( String address ) {
    byte[] a = literal( address );
    if ( a == null ) {
      return false;
    }
    for ( byte[][] cidr : TRUSTED ) {
      byte[] net = cidr[ 0 ];
      int bits = cidr[ 1 ][ 0 ] & 0xff;
      if ( net.length != a.length ) {
        continue;
      }
      boolean match = true;
      for ( int i = 0; i < bits / 8 && match; i++ ) {
        match = a[ i ] == net[ i ];
      }
      if ( match && bits % 8 != 0 ) {
        int mask = ( 0xff << ( 8 - bits % 8 ) ) & 0xff;
        match = ( a[ bits / 8 ] & mask ) == ( net[ bits / 8 ] & mask );
      }
      if ( match ) {
        return true;
      }
    }
    return false;
  }

  private static List<byte[][]> parse( String list ) {
    List<byte[][]> result = new ArrayList<>();
    for ( String part : list.split( "," ) ) {
      String p = part.trim();
      if ( p.isEmpty() ) {
        continue;
      }
      int slash = p.indexOf( '/' );
      byte[] net = literal( slash < 0 ? p : p.substring( 0, slash ) );
      if ( net == null ) {
        continue;
      }
      int bits = slash < 0 ? net.length * 8 : Integer.parseInt( p.substring( slash + 1 ) );
      result.add( new byte[][] { net, { (byte) bits } } );
    }
    return result;
  }

  /** The bytes of a literal IPv4/IPv6 address (IPv4-mapped as IPv4), or null; never resolves a name. */
  static byte[] literal( String s ) {
    s = s.trim();
    if ( s.startsWith( "[" ) && s.endsWith( "]" ) ) {
      s = s.substring( 1, s.length() - 1 );
    }
    boolean v6 = s.indexOf( ':' ) >= 0;
    if ( s.isEmpty() || ( v6 ? !s.matches( "[0-9a-fA-F:.%]+" ) : !s.matches( "[0-9.]+" ) ) ) {
      return null;
    }
    try {
      byte[] b = InetAddress.getByName( s ).getAddress();
      if ( b.length == 16 ) {
        boolean mapped = ( b[ 10 ] & 0xff ) == 0xff && ( b[ 11 ] & 0xff ) == 0xff;
        for ( int i = 0; i < 10 && mapped; i++ ) {
          mapped = b[ i ] == 0;
        }
        if ( mapped ) {
          return Arrays.copyOfRange( b, 12, 16 );
        }
      }
      return b;
    } catch ( Exception e ) {
      return null;
    }
  }
}

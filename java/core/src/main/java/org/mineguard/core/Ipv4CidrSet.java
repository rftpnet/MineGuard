package org.mineguard.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public final class Ipv4CidrSet {
  private final List<Entry> entries = new ArrayList<Entry>();

  public void add(String value) throws UnknownHostException {
    String s = value.trim();
    if (s.length() == 0 || s.indexOf(':') >= 0) return;
    int slash = s.indexOf('/');
    int bits = slash >= 0 ? Integer.parseInt(s.substring(slash + 1)) : 32;
    String ip = slash >= 0 ? s.substring(0, slash) : s;
    if (bits < 0 || bits > 32) throw new IllegalArgumentException("invalid CIDR bits: " + value);
    int mask = bits == 0 ? 0 : (int) (0xffffffffL << (32 - bits));
    entries.add(new Entry(IpReputationSet.ipv4ToInt(ip), mask));
  }

  public boolean contains(InetAddress address) {
    if (!(address instanceof Inet4Address)) return false;
    int value = IpReputationSet.bytesToInt(address.getAddress());
    for (Entry entry : entries) {
      if ((value & entry.mask) == (entry.network & entry.mask)) return true;
    }
    return false;
  }

  private static final class Entry {
    final int network;
    final int mask;
    Entry(int network, int mask) {
      this.network = network;
      this.mask = mask;
    }
  }
}

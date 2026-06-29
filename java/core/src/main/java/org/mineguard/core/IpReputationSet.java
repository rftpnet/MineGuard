package org.mineguard.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class IpReputationSet {
  private int[] ipv4 = new int[0];

  public synchronized void loadTxt(Path file) throws IOException {
    loadTxt(new Path[] { file });
  }

  public synchronized void loadTxt(Path[] files) throws IOException {
    List<Integer> vals = new ArrayList<Integer>();
    for (int i = 0; i < files.length; i++) {
      Path file = files[i];
      if (file == null || !Files.exists(file)) continue;
      BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          String s = line.trim();
          if (s.length() == 0 || s.startsWith("#") || s.indexOf(':') >= 0) continue;
          vals.add(Integer.valueOf(ipv4ToInt(s)));
        }
      } finally {
        reader.close();
      }
    }
    Collections.sort(vals);
    int unique = 0;
    Integer previous = null;
    for (int i = 0; i < vals.size(); i++) {
      Integer current = vals.get(i);
      if (previous == null || current.intValue() != previous.intValue()) vals.set(unique++, current);
      previous = current;
    }
    int[] next = new int[unique];
    for (int i = 0; i < unique; i++) next[i] = vals.get(i).intValue();
    ipv4 = next;
  }

  public synchronized boolean contains(InetAddress address) {
    if (!(address instanceof Inet4Address)) return false;
    return Arrays.binarySearch(ipv4, bytesToInt(address.getAddress())) >= 0;
  }

  public synchronized int size() {
    return ipv4.length;
  }

  public static int ipv4ToInt(String ip) throws UnknownHostException {
    InetAddress address = InetAddress.getByName(ip);
    if (!(address instanceof Inet4Address)) throw new UnknownHostException("not IPv4: " + ip);
    return bytesToInt(address.getAddress());
  }

  public static int bytesToInt(byte[] b) {
    return ((b[0] & 255) << 24) | ((b[1] & 255) << 16) | ((b[2] & 255) << 8) | (b[3] & 255);
  }
}
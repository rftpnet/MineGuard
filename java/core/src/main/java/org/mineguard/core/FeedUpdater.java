package org.mineguard.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class FeedUpdater {
  private FeedUpdater() {}

  public static void download(String value, Path destination) throws IOException {
    URL url = new URL(value);
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setConnectTimeout(8000);
    connection.setReadTimeout(15000);
    connection.setInstanceFollowRedirects(true);
    connection.setRequestProperty("User-Agent", "MineGuard/0.1.0");
    int status = connection.getResponseCode();
    if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
    Files.createDirectories(destination.getParent());
    InputStream input = new BufferedInputStream(connection.getInputStream());
    try {
      Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      input.close();
      connection.disconnect();
    }
  }
}

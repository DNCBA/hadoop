/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.crypto.key;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.CryptoUtils;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;

import javax.crypto.KeyGenerator;

import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.HADOOP_SECURITY_CRYPTO_JCEKS_KEY_SERIALFILTER;

/**
 * A provider of secret key material for Hadoop applications. Provides an
 * abstraction to separate key storage from users of encryption. It
 * is intended to support getting or storing keys in a variety of ways,
 * including third party bindings.
 * <p>
 * <code>KeyProvider</code> implementations must be thread safe.
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public abstract class KeyProvider implements Closeable {
  public static final String DEFAULT_CIPHER_NAME =
      CommonConfigurationKeysPublic.HADOOP_SECURITY_KEY_DEFAULT_CIPHER_KEY;
  public static final String DEFAULT_CIPHER =
      CommonConfigurationKeysPublic.HADOOP_SECURITY_KEY_DEFAULT_CIPHER_DEFAULT;
  public static final String DEFAULT_BITLENGTH_NAME =
      CommonConfigurationKeysPublic.HADOOP_SECURITY_KEY_DEFAULT_BITLENGTH_KEY;
  public static final int DEFAULT_BITLENGTH = CommonConfigurationKeysPublic.
      HADOOP_SECURITY_KEY_DEFAULT_BITLENGTH_DEFAULT;
  public static final String JCEKS_KEY_SERIALFILTER_DEFAULT =
      "java.lang.Enum;"
          + "java.security.KeyRep;"
          + "java.security.KeyRep$Type;"
          + "javax.crypto.spec.SecretKeySpec;"
          + "org.apache.hadoop.crypto.key.JavaKeyStoreProvider$KeyMetadata;"
          + "!*";
  public static final String JCEKS_KEY_SERIAL_FILTER = "jceks.key.serialFilter";

  private final Configuration conf;

  /**
   * The combination of both the key version name and the key material.
   */
  public static class KeyVersion {
    private final String name;
    private final String versionName;
    private final byte[] material;

    protected KeyVersion(String name, String versionName,
                         byte[] material) {
      this.name = name == null ? null : name.intern();
      this.versionName = versionName == null ? null : versionName.intern();
      this.material = material;
    }
    
    public String getName() {
      return name;
    }

    public String getVersionName() {
      return versionName;
    }

    public byte[] getMaterial() {
      return material;
    }

    @Override
    public String toString() {
      StringBuilder buf = new StringBuilder();
      buf.append("key(");
      buf.append(versionName);
      buf.append(")=");
      if (material == null) {
        buf.append("null");
      } else {
        for(byte b: material) {
          buf.append(' ');
          int right = b & 0xff;
          if (right < 0x10) {
            buf.append('0');
          }
          buf.append(Integer.toHexString(right));
        }
      }
      return buf.toString();
    }

    @Override
    public boolean equals(Object rhs) {
      if (this == rhs) {
        return true;
      }
      if (rhs == null || getClass() != rhs.getClass()) {
        return false;
      }
      final KeyVersion kv = (KeyVersion) rhs;
      return Objects.equals(name, kv.name)
          && Objects.equals(versionName, kv.versionName)
          && Arrays.equals(material, kv.material);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, versionName, Arrays.hashCode(material));
    }
  }

  /**
   * Key metadata that is associated with the key.
   */
  public static class Metadata {
    private final static String CIPHER_FIELD = "cipher";
    private final static String BIT_LENGTH_FIELD = "bitLength";
    private final static String CREATED_FIELD = "created";
    private final static String DESCRIPTION_FIELD = "description";
    private final static String VERSIONS_FIELD = "versions";
    private final static String ATTRIBUTES_FIELD = "attributes";

    private final String cipher;
    private final int bitLength;
    private final String description;
    private final Date created;
    private int versions;
    private Map<String, String> attributes;

    protected Metadata(String cipher, int bitLength, String description,
        Map<String, String> attributes, Date created, int versions) {
      this.cipher = cipher;
      this.bitLength = bitLength;
      this.description = description;
      this.attributes = (attributes == null || attributes.isEmpty())
                        ? null : attributes;
      this.created = created;
      this.versions = versions;
    }

    public String toString() {
      final StringBuilder metaSB = new StringBuilder();
      metaSB.append("cipher: ").append(cipher).append(", ");
      metaSB.append("length: ").append(bitLength).append(", ");
      metaSB.append("description: ").append(description).append(", ");
      metaSB.append("created: ").append(created).append(", ");
      metaSB.append("version: ").append(versions).append(", ");
      metaSB.append("attributes: ");
      if ((attributes != null) && !attributes.isEmpty()) {
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
          metaSB.append("[");
          metaSB.append(attribute.getKey());
          metaSB.append("=");
          metaSB.append(attribute.getValue());
          metaSB.append("], ");
        }
        metaSB.deleteCharAt(metaSB.length() - 2);  // remove last ', '
      } else {
        metaSB.append("null");
      }
      return metaSB.toString();
    }

    public String getDescription() {
      return description;
    }

    public Date getCreated() {
      return created;
    }

    public String getCipher() {
      return cipher;
    }

    public Map<String, String> getAttributes() {
      return (attributes == null) ? Collections.emptyMap() : attributes;
    }

    /**
     * Get the algorithm from the cipher.
     * @return the algorithm name
     */
    public String getAlgorithm() {
      int slash = cipher.indexOf('/');
      if (slash == - 1) {
        return cipher;
      } else {
        return cipher.substring(0, slash);
      }
    }

    public int getBitLength() {
      return bitLength;
    }

    public int getVersions() {
      return versions;
    }

    protected int addVersion() {
      return versions++;
    }

    /**
     * Serialize the metadata to a set of bytes.
     * @return the serialized bytes
     * @throws IOException raised on errors performing I/O.
     */
    protected byte[] serialize() throws IOException {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      JsonWriter writer = new JsonWriter(
          new OutputStreamWriter(buffer, StandardCharsets.UTF_8));
      try {
        writer.beginObject();
        if (cipher != null) {
          writer.name(CIPHER_FIELD).value(cipher);
        }
        if (bitLength != 0) {
          writer.name(BIT_LENGTH_FIELD).value(bitLength);
        }
        if (created != null) {
          writer.name(CREATED_FIELD).value(created.getTime());
        }
        if (description != null) {
          writer.name(DESCRIPTION_FIELD).value(description);
        }
        if (attributes != null && attributes.size() > 0) {
          writer.name(ATTRIBUTES_FIELD).beginObject();
          for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            writer.name(attribute.getKey()).value(attribute.getValue());
          }
          writer.endObject();
        }
        writer.name(VERSIONS_FIELD).value(versions);
        writer.endObject();
        writer.flush();
      } finally {
        writer.close();
      }
      return buffer.toByteArray();
    }

    /**
     * Deserialize a new metadata object from a set of bytes.
     * @param bytes the serialized metadata
     * @throws IOException raised on errors performing I/O.
     */
    protected Metadata(byte[] bytes) throws IOException {
      String cipher = null;
      int bitLength = 0;
      Date created = null;
      int versions = 0;
      String description = null;
      Map<String, String> attributes = null;
      JsonReader reader =
          new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes),
              StandardCharsets.UTF_8));
      try {
        reader.beginObject();
        while (reader.hasNext()) {
          String field = reader.nextName();
          if (CIPHER_FIELD.equals(field)) {
            cipher = reader.nextString();
          } else if (BIT_LENGTH_FIELD.equals(field)) {
            bitLength = reader.nextInt();
          } else if (CREATED_FIELD.equals(field)) {
            created = new Date(reader.nextLong());
          } else if (VERSIONS_FIELD.equals(field)) {
            versions = reader.nextInt();
          } else if (DESCRIPTION_FIELD.equals(field)) {
            description = reader.nextString();
          } else if (ATTRIBUTES_FIELD.equalsIgnoreCase(field)) {
            reader.beginObject();
            attributes = new HashMap<String, String>();
            while (reader.hasNext()) {
              attributes.put(reader.nextName(), reader.nextString());
            }
            reader.endObject();
          }
        }
        reader.endObject();
      } finally {
        reader.close();
      }
      this.cipher = cipher;
      this.bitLength = bitLength;
      this.created = created;
      this.description = description;
      this.attributes = attributes;
      this.versions = versions;
    }
  }

  /**
   * Options when creating key objects.
   */
  public static class Options {
    private String cipher;
    private int bitLength;
    private String description;
    private Map<String, String> attributes;

    public Options(Configuration conf) {
      cipher = conf.get(DEFAULT_CIPHER_NAME, DEFAULT_CIPHER);
      bitLength = conf.getInt(DEFAULT_BITLENGTH_NAME, DEFAULT_BITLENGTH);
    }

    public Options setCipher(String cipher) {
      this.cipher = cipher;
      return this;
    }

    public Options setBitLength(int bitLength) {
      this.bitLength = bitLength;
      return this;
    }

    public Options setDescription(String description) {
      this.description = description;
      return this;
    }

    public Options setAttributes(Map<String, String> attributes) {
      if (attributes != null) {
        if (attributes.containsKey(null)) {
          throw new IllegalArgumentException("attributes cannot have a NULL key");
        }
        this.attributes = new HashMap<String, String>(attributes);
      }
      return this;
    }

    public String getCipher() {
      return cipher;
    }

    public int getBitLength() {
      return bitLength;
    }

    public String getDescription() {
      return description;
    }

    public Map<String, String> getAttributes() {
      return (attributes == null) ? Collections.emptyMap() : attributes;
    }

    @Override
    public String toString() {
      return "Options{" +
          "cipher='" + cipher + '\'' +
          ", bitLength=" + bitLength +
          ", description='" + description + '\'' +
          ", attributes=" + attributes +
          '}';
    }
  }

  /**
   * Constructor.
   * 
   * @param conf configuration for the provider
   */
  public KeyProvider(Configuration conf) {
    this.conf = new Configuration(conf);
    // Added for HADOOP-15473. Configured serialFilter property fixes
    // java.security.UnrecoverableKeyException in JDK 8u171.
    if(System.getProperty(JCEKS_KEY_SERIAL_FILTER) == null) {
      String serialFilter =
              conf.get(HADOOP_SECURITY_CRYPTO_JCEKS_KEY_SERIALFILTER,
                      JCEKS_KEY_SERIALFILTER_DEFAULT);
      System.setProperty(JCEKS_KEY_SERIAL_FILTER, serialFilter);
    }
    CryptoUtils.getJceProvider(conf);
  }

  /**
   * Return the provider configuration.
   * 
   * @return the provider configuration
   */
  public Configuration getConf() {
    return conf;
  }
  
  /**
   * A helper function to create an options object.
   * @param conf the configuration to use
   * @return a new options object
   */
  public static Options options(Configuration conf) {
    return new Options(conf);
  }

  /**
   * Indicates whether this provider represents a store
   * that is intended for transient use - such as the UserProvider
   * is. These providers are generally used to provide access to
   * keying material rather than for long term storage.
   * @return true if transient, false otherwise
   */
  public boolean isTransient() {
    return false;
  }

  /**
   * Get the key material for a specific version of the key. This method is used
   * when decrypting data.
   * @param versionName the name of a specific version of the key
   * @return the key material
   * @throws IOException raised on errors performing I/O.
   */
  public abstract KeyVersion getKeyVersion(String versionName
                                            ) throws IOException;

  /**
   * Get the key names for all keys.
   * @return the list of key names
   * @throws IOException raised on errors performing I/O.
   */
  public abstract List<String> getKeys() throws IOException;

  /**
   * Get key metadata in bulk.
   * @param names the names of the keys to get
   * @throws IOException raised on errors performing I/O.
   * @return Metadata Array.
   */
  public Metadata[] getKeysMetadata(String... names) throws IOException {
    Metadata[] result = new Metadata[names.length];
    for (int i=0; i < names.length; ++i) {
      result[i] = getMetadata(names[i]);
    }
    return result;
  }

  /**
   * Get the key material for all versions of a specific key name.
   *
   * @param name the base name of the key.
   * @return the list of key material
   * @throws IOException raised on errors performing I/O.
   */
  public abstract List<KeyVersion> getKeyVersions(String name) throws IOException;

  /**
   * Get the current version of the key, which should be used for encrypting new
   * data.
   * @param name the base name of the key
   * @return the version name of the current version of the key or null if the
   *    key version doesn't exist
   * @throws IOException raised on errors performing I/O.
   */
  public KeyVersion getCurrentKey(String name) throws IOException {
    Metadata meta = getMetadata(name);
    if (meta == null) {
      return null;
    }
    return getKeyVersion(buildVersionName(name, meta.getVersions() - 1));
  }

  /**
   * Get metadata about the key.
   * @param name the basename of the key
   * @return the key's metadata or null if the key doesn't exist
   * @throws IOException raised on errors performing I/O.
   */
  public abstract Metadata getMetadata(String name) throws IOException;

  /**
   * Create a new key. The given key must not already exist.
   * @param name the base name of the key
   * @param material the key material for the first version of the key.
   * @param options the options for the new key.
   * @return the version name of the first version of the key.
   * @throws IOException raised on errors performing I/O.
   */
  public abstract KeyVersion createKey(String name, byte[] material,
                                       Options options) throws IOException;

  /**
   * Get the algorithm from the cipher.
   *
   * @return the algorithm name
   */
  private String getAlgorithm(String cipher) {
    int slash = cipher.indexOf('/');
    if (slash == -1) {
      return cipher;
    } else {
      return cipher.substring(0, slash);
    }
  }

  /**
   * Generates a key material.
   *
   * @param size length of the key.
   * @param algorithm algorithm to use for generating the key.
   * @return the generated key.
   * @throws NoSuchAlgorithmException no such algorithm exception.
   */
  protected byte[] generateKey(int size, String algorithm)
      throws NoSuchAlgorithmException {
    algorithm = getAlgorithm(algorithm);
    KeyGenerator keyGenerator = KeyGenerator.getInstance(algorithm);
    keyGenerator.init(size);
    byte[] key = keyGenerator.generateKey().getEncoded();
    return key;
  }

  /**
   * Create a new key generating the material for it.
   * The given key must not already exist.
   * <p>
   * This implementation generates the key material and calls the
   * {@link #createKey(String, byte[], Options)} method.
   *
   * @param name the base name of the key
   * @param options the options for the new key.
   * @return the version name of the first version of the key.
   * @throws IOException raised on errors performing I/O.
   * @throws NoSuchAlgorithmException no such algorithm exception.
   */
  public KeyVersion createKey(String name, Options options)
      throws NoSuchAlgorithmException, IOException {
    byte[] material = generateKey(options.getBitLength(), options.getCipher());
    return createKey(name, material, options);
  }

  /**
   * Delete the given key.
   * @param name the name of the key to delete
   * @throws IOException raised on errors performing I/O.
   */
  public abstract void deleteKey(String name) throws IOException;

  /**
   * Roll a new version of the given key.
   * @param name the basename of the key
   * @param material the new key material
   * @return the name of the new version of the key
   * @throws IOException raised on errors performing I/O.
   */
  public abstract KeyVersion rollNewVersion(String name,
                                             byte[] material
                                            ) throws IOException;

  /**
   * Can be used by implementing classes to close any resources
   * that require closing
   */
  public void close() throws IOException {
    // NOP
  }

  /**
   * Roll a new version of the given key generating the material for it.
   * <p>
   * This implementation generates the key material and calls the
   * {@link #rollNewVersion(String, byte[])} method.
   *
   * @param name the basename of the key
   * @return the name of the new version of the key
   * @throws IOException              raised on errors performing I/O.
   * @throws NoSuchAlgorithmException This exception is thrown when a particular
   *                                  cryptographic algorithm is requested
   *                                  but is not available in the environment.
   */
  public KeyVersion rollNewVersion(String name) throws NoSuchAlgorithmException,
                                                       IOException {
    Metadata meta = getMetadata(name);
    if (meta == null) {
      throw new IOException("Can't find Metadata for key " + name);
    }

    byte[] material = generateKey(meta.getBitLength(), meta.getCipher());
    return rollNewVersion(name, material);
  }

  /**
   * Can be used by implementing classes to invalidate the caches. This could be
   * used after rollNewVersion to provide a strong guarantee to return the new
   * version of the given key.
   *
   * @param name the basename of the key
   * @throws IOException raised on errors performing I/O.
   */
  public void invalidateCache(String name) throws IOException {
    // NOP
  }

  /**
   * Ensures that any changes to the keys are written to persistent store.
   * @throws IOException raised on errors performing I/O.
   */
  public abstract void flush() throws IOException;

  /**
   * Split the versionName in to a base name. Converts "/aaa/bbb@3" to
   * "/aaa/bbb".
   * @param versionName the version name to split
   * @return the base name of the key
   * @throws IOException raised on errors performing I/O.
   */
  public static String getBaseName(String versionName) throws IOException {
    Objects.requireNonNull(versionName, "VersionName cannot be null");
    int div = versionName.lastIndexOf('@');
    if (div == -1) {
      throw new IOException("No version in key path " + versionName);
    }
    return versionName.substring(0, div);
  }

  /**
   * Build a version string from a basename and version number. Converts
   * "/aaa/bbb" and 3 to "/aaa/bbb@3".
   * @param name the basename of the key
   * @param version the version of the key
   * @return the versionName of the key.
   */
  protected static String buildVersionName(String name, int version) {
    return name + "@" + version;
  }

  /**
   * Find the provider with the given key.
   *
   * @param providerList the list of providers
   * @param keyName the key name we are looking for.
   * @return the KeyProvider that has the key
   * @throws IOException raised on errors performing I/O.
   */
  public static KeyProvider findProvider(List<KeyProvider> providerList,
                                         String keyName) throws IOException {
    for(KeyProvider provider: providerList) {
      if (provider.getMetadata(keyName) != null) {
        return provider;
      }
    }
    throw new IOException("Can't find KeyProvider for key " + keyName);
  }

  /**
   * Does this provider require a password? This means that a password is
   * required for normal operation, and it has not been found through normal
   * means. If true, the password should be provided by the caller using
   * setPassword().
   * @return Whether or not the provider requires a password
   * @throws IOException raised on errors performing I/O.
   */
  public boolean needsPassword() throws IOException {
    return false;
  }

  /**
   * If a password for the provider is needed, but is not provided, this will
   * return a warning and instructions for supplying said password to the
   * provider.
   * @return A warning and instructions for supplying the password
   */
  public String noPasswordWarning() {
    return null;
  }

  /**
   * If a password for the provider is needed, but is not provided, this will
   * return an error message and instructions for supplying said password to
   * the provider.
   * @return An error message and instructions for supplying the password
   */
  public String noPasswordError() {
    return null;
  }
}

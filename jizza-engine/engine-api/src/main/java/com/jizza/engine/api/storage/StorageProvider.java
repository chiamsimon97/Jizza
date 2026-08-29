package com.jizza.engine.api.storage;

/** Object storage behind an interface so local fs now and S3/GCS later are a config swap (R47). */
public interface StorageProvider {

  String put(byte[] data, String name);

  byte[] get(String ref);

  void delete(String ref);
}

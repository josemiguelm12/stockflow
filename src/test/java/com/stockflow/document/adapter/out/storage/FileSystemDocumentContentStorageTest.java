package com.stockflow.document.adapter.out.storage;

import com.stockflow.shared.config.DocumentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream; import java.nio.file.Files; import java.nio.file.Path; import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemDocumentContentStorageTest {
 @TempDir Path directory;
 @Test void storesWithInternalUuidAndRetainsContent() throws Exception {
  var storage=new FileSystemDocumentContentStorage(new DocumentProperties(directory,10L)); UUID key=UUID.randomUUID();
  storage.store(key,new ByteArrayInputStream("hello".getBytes()));
  assertThat(storage.exists(key)).isTrue(); try (var input = storage.open(key)) { assertThat(input.readAllBytes()).isEqualTo("hello".getBytes()); }
  storage.discard(key); assertThat(Files.exists(directory.resolve(key.toString()))).isFalse();
 }
 @Test void rejectsEmptyAndOversizedContentWithoutPublishingFile() {
  var storage=new FileSystemDocumentContentStorage(new DocumentProperties(directory,2L)); UUID key=UUID.randomUUID();
  assertThatThrownBy(() -> storage.store(key,new ByteArrayInputStream(new byte[0]))).isInstanceOf(Exception.class);
  assertThatThrownBy(() -> storage.store(key,new ByteArrayInputStream(new byte[3]))).isInstanceOf(Exception.class);
  assertThat(Files.exists(directory.resolve(key.toString()))).isFalse();
 }
}

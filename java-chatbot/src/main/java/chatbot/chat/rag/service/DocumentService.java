package chatbot.chat.rag.service;

import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.domain.DocumentRecord;
import chatbot.chat.rag.store.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.dao.DuplicateKeyException;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Service
public class DocumentService {
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private final DocumentRepository repository;
    private final Path uploads;
    public DocumentService(RagProperties properties, DocumentRepository repository) throws IOException {
        this.repository = repository;
        uploads = properties.dataDir().resolve("uploads").toAbsolutePath().normalize();
        Files.createDirectories(uploads);
    }
    public DocumentRecord get(String id) {
        validateId(id);
        return repository.find(id).orElseThrow(() -> new NoSuchElementException("文档不存在"));
    }
    public List<DocumentRecord> list(int limit, int offset) { return repository.list(limit, offset); }
    public DocumentRecord upload(MultipartFile file) throws IOException {
        String name = Objects.requireNonNullElse(file.getOriginalFilename(), "").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isBlank() || name.length() > 255) throw new IllegalArgumentException("文件名为空或超过255字符");
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("pdf","doc","docx","txt","md").contains(extension))
            throw new IllegalArgumentException("当前支持 PDF、DOC、DOCX、TXT、MD；图片和扫描件 OCR 尚未启用");
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw new IllegalArgumentException("文件必须非空且不超过20 MiB");
        Path temp = Files.createTempFile(uploads, "upload-", ".tmp");
        String id = UUID.randomUUID().toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (var input = file.getInputStream(); var output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) {
                    count += n;
                    if (count > MAX_BYTES) throw new IllegalArgumentException("文件超过20 MiB");
                    digest.update(buffer, 0, n); output.write(buffer, 0, n);
                }
            }
            if (count == 0) throw new IllegalArgumentException("文件不能为空");
            String hash = HexFormat.of().formatHex(digest.digest());
            var existing = repository.byHash(hash);
            if (existing.isPresent()) return existing.get();
            Path target = path(id);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            try { return repository.insert(id, name, hash); }
            catch (DuplicateKeyException e) {
                Files.deleteIfExists(target);
                return repository.byHash(hash).orElseThrow(() -> e);
            } catch (RuntimeException e) { Files.deleteIfExists(target); throw e; }
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        finally { Files.deleteIfExists(temp); }
    }
    public DocumentRecord retry(String id) {
        var doc = get(id);
        if (!Set.of("FAILED","READY").contains(doc.status()))
            throw new IllegalArgumentException("仅失败或已就绪的文档可重新索引");
        if (!repository.transition(id, doc.status(), "QUEUED"))
            throw new IllegalArgumentException("文档状态已变化，请刷新后重试");
        return get(id);
    }
    public void delete(String id) { get(id); repository.markDeleting(id); }
    public Path path(String id) {
        validateId(id);
        Path result = uploads.resolve(id + ".bin").normalize();
        if (!result.startsWith(uploads)) throw new IllegalArgumentException("无效文件路径");
        return result;
    }
    public Path content(String id) {
        var doc = get(id);
        if (doc.status().equals("DELETING")) throw new NoSuchElementException("文档已删除");
        return path(id);
    }
    private static void validateId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("无效文档 ID");
    }
}

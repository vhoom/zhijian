package github.vhoom.zhijian.data.store.apps;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import github.vhoom.zhijian.data.model.AppInfo;

/**
 * 应用清单文件快照：在同目录写入并同步临时文件，原子替换后才发布新内容。
 *
 * 维护日期：2026年11月10日
 */
public final class AppCatalogFileStore implements AppCatalogCache {
    private static final int MAGIC = 0x41505053;
    private static final int FORMAT = 1;
    /** 损坏的计数不得直接用于分配任意大小的集合。 */
    private static final int MAX_APP_COUNT = 100_000;
    private final Path file;

    /**
     * 绑定应用私有目录中的固定清单文件，构造时不执行磁盘读写。
     * @param file 快照文件路径，临时写入也限定在其父目录
     */
    public AppCatalogFileStore(Path file) {
        this.file = Objects.requireNonNull(file).toAbsolutePath();
    }

    /**
     * 完整读取已提交快照，缺失、空列表和损坏分别处理。
     * @return 文件尚不存在时返回 null，其余成功读取返回完整清单
     * @throws IOException 头部、记录或文件内容无效
     */
    @Override public List<AppInfo> read() throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT) {
                throw new IOException("Invalid application catalog format");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_APP_COUNT) throw new IOException("Invalid application catalog count");
            List<AppInfo> apps = new ArrayList<>(count);
            Set<String> packages = new HashSet<>();
            for (int index = 0; index < count; index++) {
                String packageName = input.readUTF();
                String label = input.readUTF();
                if (packageName.isEmpty() || !packages.add(packageName)) {
                    throw new IOException("Invalid application catalog entry");
                }
                apps.add(new AppInfo(packageName, label));
            }
            if (input.read() != -1) throw new IOException("Unexpected application catalog content");
            return apps;
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    /**
     * 临时文件完整落盘后在同一文件系统内原子替换，写入失败不覆盖旧清单。
     * @param apps 新的完整应用清单
     * @throws IOException 写入、同步或原子替换失败
     */
    @Override public void write(List<AppInfo> apps) throws IOException {
        if (apps.size() > MAX_APP_COUNT) throw new IOException("Application catalog is too large");
        Path directory = file.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "app-catalog-", ".tmp");
        try {
            try (FileOutputStream raw = new FileOutputStream(temporary.toFile());
                    DataOutputStream output = new DataOutputStream(new BufferedOutputStream(raw))) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT);
                output.writeInt(apps.size());
                Set<String> packages = new HashSet<>();
                for (AppInfo app : apps) {
                    if (app.packageName.isEmpty() || !packages.add(app.packageName)) {
                        throw new IOException("Invalid application catalog entry");
                    }
                    output.writeUTF(app.packageName);
                    output.writeUTF(app.label);
                }
                output.flush();
                raw.getFD().sync();
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            removeTemporary(temporary);
        }
    }

    /**
     * 清理本次未被索引的临时文件，不把清理失败误报成快照提交失败。
     * @param temporary 本次写入在同目录创建的临时文件
     */
    private static void removeTemporary(Path temporary) {
        try { Files.deleteIfExists(temporary); }
        catch (IOException ignored) {
            // 临时文件不参与清单读取，无法清理也不会覆盖已提交快照。
        }
    }
}

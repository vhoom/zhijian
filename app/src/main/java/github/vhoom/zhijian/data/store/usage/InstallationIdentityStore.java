package github.vhoom.zhijian.data.store.usage;

import android.content.Context;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 不参与系统备份的安装身份；覆盖安装保留，卸载重装及迁移到另一安装后重新生成。
 *
 * 维护日期：2026年11月15日
 */
public final class InstallationIdentityStore {
    /** 文件适配器不持有页面或进程状态。 */
    private InstallationIdentityStore() { }

    /**
     * @param context 取得 no-backup 私有目录的应用上下文，调用方在存储后台队列执行
     * @return 已同步保存的本次安装身份
     * @throws IOException 文件损坏或保存失败，不以另一个身份静默覆盖现有数据
     */
    public static synchronized String readOrCreate(Context context) throws IOException {
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "usage_installation_id"));
        if (file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists()) {
            String identity = new String(file.readFully(), StandardCharsets.UTF_8);
            try {
                if (!UUID.fromString(identity).toString().equals(identity)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid usage installation identity", invalid);
            }
            return identity;
        }
        String identity = UUID.randomUUID().toString();
        byte[] bytes = identity.getBytes(StandardCharsets.UTF_8);
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(bytes);
            stream.getFD().sync();
            file.finishWrite(stream);
        } catch (IOException | RuntimeException failure) {
            file.failWrite(stream);
            throw failure;
        }
        if (!java.util.Arrays.equals(bytes, file.readFully())) throw new IOException("Installation identity commit failed");
        return identity;
    }
}

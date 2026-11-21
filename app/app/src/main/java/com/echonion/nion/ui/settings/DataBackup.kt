package com.echonion.nion.ui.settings

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import com.echonion.nion.core
import com.echonion.nion.util.AvatarStorage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据备份/恢复（导出 / 导入）实现。
 *
 * 备份格式（zip）与 0.9.0 之前的版本保持兼容：旧版本导入时会把 zip 里的每个条目
 * 直接解压到 `getExternalFilesDir(null)`，并期望根目录存在 `nion.db`。因此：
 * - 数据库始终以根条目 `nion.db` 写入（内容取自内部 `databases/nion.db` 的快照）；
 * - 表情包 / 头像等外置目录文件保持原有的相对路径（`stickers/...`、`avatar/...`）；
 * - 任务附件额外使用 `attachments/` 前缀，导入时还原到 `filesDir/attachments/`。
 *
 * 0.9.0 起主库移到内部存储（外置 FUSE 存储上的 SQLite 写入不可靠），所以导出必须先
 * checkpoint 再拷贝，导入则先写暂存文件、下次启动时由 NionApp 在打开连接前替换正式库。
 */

/** 日志 TAG */
private const val TAG = "DataBackup"

/** 正式数据库文件名（内部 databases/ 目录 + zip 根条目名） */
private const val DB_NAME = "nion.db"

/** 旧版本从外置存储迁移后留下的数据库留档名（0.9.0~0.9.3 导出的备份只有这一份） */
private const val LEGACY_DB_NAME = "nion.db.migrated"

/** 导入暂存库文件名，与正式库同目录，由 NionApp 在下次启动时安装 */
internal const val STAGING_DB_NAME = "nion.db.import"

/** 表情包目录名（位于外置私有目录下） */
private const val STICKERS_DIR_NAME = "stickers"

/** 头像目录名（位于外置私有目录下） */
private const val AVATAR_DIR_NAME = "avatar"

/** 任务附件目录名（位于内部 filesDir 下） */
private const val ATTACHMENTS_DIR_NAME = "attachments"

/**
 * 取出表情包 / 头像所在的数据目录。
 *
 * 正常情况下是外置私有目录 `Android/data/<包名>/files`；外置存储不可用（返回 null）时
 * 退化为内部 `nion_data` 目录，与历史代码中的回退规则保持一致，保证已有文件仍能被找到。
 *
 * @param context 任意 Context
 * @return 数据目录（不保证已存在）
 */
private fun appDataDir(context: Context): File =
    context.getExternalFilesDir(null) ?: context.getDir("nion_data", Context.MODE_PRIVATE)

/**
 * 导出数据到指定 URI（zip 格式）。
 *
 * 步骤：
 * 1. 若当前头像设置是 `content://` URI（系统照片选择器授权），先把图片字节复制到
 *    `<ext>/avatar/custom_avatar.<ext>` 并把设置改写为 `file://` URI，否则备份里的头像
 *    在新设备上无法打开（授权只在本机有效）。授权失效时保持原设置不动。
 * 2. 对内部库 `databases/nion.db` 执行 `wal_checkpoint(TRUNCATE)`，再以根条目 `nion.db` 写入。
 * 3. 递归打包外置数据目录（跳过数据库本体、迁移留档和 SQLite 临时文件）。
 * 4. 递归打包 `filesDir/attachments/`，条目前缀为 `attachments/`。
 *
 * @param context 任意 Context（内部会取 applicationContext 访问 Rust core）
 * @param destUri SAF CreateDocument 返回的目标文件 URI
 * @throws IllegalStateException 内部数据库不存在或目标文件无法写入时抛出（由调用方提示"导出失败"）
 */
internal fun exportData(context: Context, destUri: Uri) {
    val extDir = appDataDir(context)

    // 先把 content:// 头像实体化：备份里只应该出现 file:// 或相对路径的头像文件
    healContentAvatar(context)

    val dbFile = context.getDatabasePath(DB_NAME)
    if (!dbFile.exists()) {
        // 没有数据库就没有可恢复的内容，直接失败比导出一个空备份更安全
        throw IllegalStateException("找不到应用数据库，无法导出")
    }
    // 合并 -wal 到主库，保证复制的 nion.db 是完整快照
    checkpointDatabase(dbFile)

    val rawOut = context.contentResolver.openOutputStream(destUri)
        ?: throw IllegalStateException("无法写入所选位置")
    ZipOutputStream(BufferedOutputStream(rawOut)).use { zip ->
        writeZipEntry(zip, dbFile, DB_NAME)
        // 外置数据目录：表情包、头像等（递归，内部按规则跳过数据库与临时文件）
        packDirectory(zip, extDir, "")
        // 任务附件在内部 filesDir 下，用 attachments/ 前缀区分归属
        val attachmentsDir = File(context.filesDir, ATTACHMENTS_DIR_NAME)
        if (attachmentsDir.exists()) {
            packDirectory(zip, attachmentsDir, ATTACHMENTS_DIR_NAME)
        }
    }
    Log.i(TAG, "导出完成: ${dbFile.absolutePath} + ${extDir.absolutePath} → $destUri")
}

/**
 * 从 zip URI 导入数据。
 *
 * 分两遍读同一个 SAF URI（`openInputStream` 可以重复调用）：
 * - 第一遍只收集条目名，用来定位数据库条目。备份里既没有 `nion.db` 也没有
 *   `nion.db.migrated` 时立即抛错，此时尚未改动任何用户数据。
 * - 第二遍落盘：数据库写入暂存文件 `databases/nion.db.import`（不碰正在使用的正式库），
 *   附件写入 `filesDir/attachments/`，其余文件写入外置数据目录。
 *
 * 落盘后还会修正暂存库里的绝对路径（表情包、附件、头像），使它们指向新设备的实际目录。
 * 真正的库替换在下次启动时由 NionApp.prepareDatabasePath() 完成。
 *
 * @param context 任意 Context
 * @param srcUri SAF OpenDocument 返回的 zip 文件 URI
 * @throws IllegalStateException 备份中没有数据库条目时抛出
 */
internal fun importData(context: Context, srcUri: Uri) {
    val extDir = appDataDir(context)
    val attachmentsDir = File(context.filesDir, ATTACHMENTS_DIR_NAME)

    // ── 第一遍：只读条目名 ──
    val entryNames = mutableSetOf<String>()
    openZipInput(context, srcUri).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            entryNames += entry.name
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    // 0.9.0~0.9.3 升级过的用户导出时外置目录里只有迁移留档 nion.db.migrated，
    // 这类备份用留档里的库恢复，避免报"没有数据库"
    val dbEntryName = when {
        DB_NAME in entryNames -> DB_NAME
        LEGACY_DB_NAME in entryNames -> LEGACY_DB_NAME
        else -> throw IllegalStateException("备份文件中没有数据库")
    }

    // ── 准备暂存库：与正式库同目录，保证 rename 是同一文件系统内的原子操作 ──
    val staging = context.getDatabasePath(STAGING_DB_NAME)
    val stagingDir = staging.parentFile ?: throw IllegalStateException("无法定位数据库目录")
    stagingDir.mkdirs()
    // 清掉上次失败留下的暂存文件及其日志，避免 SQLite 回放旧 -wal 内容
    deleteWithSidecars(staging)

    // ── 第二遍：真正落盘 ──
    // 整体包在 try 里：中途失败时删除半成品暂存库，否则下次启动会把一个不完整/损坏的库
    // 当成正式库装上，用户看到的是"导入失败"却丢了原数据
    try {
        openZipInput(context, srcUri).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val isDirectory = entry.isDirectory || name.endsWith("/")
                when {
                    // 两份数据库条目只取需要的那一份，另一份是过期快照
                    name == DB_NAME || name == LEGACY_DB_NAME -> {
                        if (name == dbEntryName && !isDirectory) {
                            FileOutputStream(staging).use { output -> zip.copyTo(output) }
                        }
                    }
                    // SQLite 的 -wal/-shm/-journal 必定属于旧设备上的库，导入后会污染新库
                    isZipSidecar(name) -> Unit
                    // 附件放在内部 filesDir，用前缀判断归属（也兜住子目录）
                    name == ATTACHMENTS_DIR_NAME || name.startsWith("$ATTACHMENTS_DIR_NAME/") ->
                        extractEntry(zip, context.filesDir, name, isDirectory)
                    else -> extractEntry(zip, extDir, name, isDirectory)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // 数据库条目是目录或为空时不会有文件落盘，不能当作成功（否则会白白重启一次）
        if (!staging.exists()) throw IllegalStateException("备份文件中的数据库条目无效")

        rewriteStagedDatabasePaths(staging, extDir, attachmentsDir)
    } catch (e: Exception) {
        deleteWithSidecars(staging)
        throw e
    }
    Log.i(TAG, "导入完成，暂存库: ${staging.absolutePath}")
}

/**
 * 打开备份 zip 的输入流（每次调用都会新建一条流，供两遍扫描使用）。
 *
 * @param context 用于 contentResolver
 * @param uri SAF 文件 URI
 * @return 带缓冲的 ZipInputStream，调用方负责关闭
 */
private fun openZipInput(context: Context, uri: Uri): ZipInputStream {
    val rawIn = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("无法读取备份文件")
    return ZipInputStream(BufferedInputStream(rawIn))
}

/**
 * 把 content:// 头像复制成外置目录里的实体文件，并改写设置项。
 *
 * 只处理 `content://`：`file://` 和空值都已经是可备份的形态。复制失败（授权过期、
 * 文件被删除）时保持原设置不变，导出继续，避免因头像问题导致整个备份失败。
 *
 * @param context 任意 Context
 */
private fun healContentAvatar(context: Context) {
    try {
        val core = (context.applicationContext as Application).core()
        val current = core.getSetting("companion_avatar_uri") ?: return
        if (!current.startsWith("content://")) return

        val uri = Uri.parse(current)
        val dest = File(
            AvatarStorage.avatarDir(context),
            "custom_avatar.${AvatarStorage.imageExtension(context, uri)}",
        )
        if (!AvatarStorage.copyToFile(context, uri, dest)) {
            Log.w(TAG, "头像 content URI 已失效，保持原设置: $uri")
            return
        }
        core.setSetting("companion_avatar_uri", Uri.fromFile(dest).toString())
        Log.i(TAG, "头像已实体化: $dest")
    } catch (e: Exception) {
        Log.w(TAG, "导出前实体化头像失败，保持原设置", e)
    }
}

/**
 * 对数据库执行 `PRAGMA wal_checkpoint(TRUNCATE)`，把 -wal 内容合并进主库。
 *
 * Rust 端在进程生命周期内一直持有连接，这里额外开一个读写连接做 checkpoint；
 * 失败（例如被占用）不阻断导出：delete 日志模式下主库文件本身就是最新状态。
 *
 * @param dbFile 数据库文件（内部 databases/nion.db）
 */
private fun checkpointDatabase(dbFile: File) {
    try {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            .use { db ->
                // wal_checkpoint 会返回一行结果，Android 的 execSQL 拒绝带结果的语句
                // （"Queries can be performed using ... rawQuery methods only"），必须用 rawQuery 并读一次游标才会真正执行
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
    } catch (e: Exception) {
        Log.w(TAG, "wal_checkpoint 失败，按当前主库文件导出", e)
    }
}

/**
 * 递归把目录内容写入 zip。
 *
 * @param zip 目标 zip 流
 * @param dir 待打包目录
 * @param prefix 条目前缀（""=根，或 "attachments"），子目录会自动扩展前缀
 */
private fun packDirectory(zip: ZipOutputStream, dir: File, prefix: String) {
    dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
        val entryName = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
        if (shouldSkipBackupEntry(entryName)) return@forEach
        if (child.isDirectory) {
            packDirectory(zip, child, entryName)
        } else {
            writeZipEntry(zip, child, entryName)
        }
    }
}

/**
 * 判断某个条目是否不应进入备份。
 *
 * 数据库本体 / 迁移留档由根条目 `nion.db` 统一提供（避免打包出过期快照），
 * 而 `*-wal`、`*-shm`、`*-journal` 属于运行中的临时文件，拷贝出去会破坏一致性。
 *
 * @param entryName zip 内的条目名
 * @return true 表示跳过
 */
private fun shouldSkipBackupEntry(entryName: String): Boolean {
    val fileName = entryName.substringAfterLast('/')
    if (fileName == DB_NAME || fileName == LEGACY_DB_NAME) return true
    return isZipSidecar(fileName)
}

/**
 * 判断条目名是否为 SQLite 的临时伴随文件。
 *
 * @param name 条目名或文件名
 * @return true 表示 -wal / -shm / -journal 文件
 */
private fun isZipSidecar(name: String): Boolean {
    val fileName = name.substringAfterLast('/')
    return fileName.endsWith("-wal") || fileName.endsWith("-shm") || fileName.endsWith("-journal")
}

/**
 * 把一个文件写入 zip 的指定条目。
 *
 * @param zip 目标 zip 流
 * @param file 源文件
 * @param entryName zip 内条目名
 */
private fun writeZipEntry(zip: ZipOutputStream, file: File, entryName: String) {
    zip.putNextEntry(ZipEntry(entryName))
    file.inputStream().use { input -> input.copyTo(zip) }
    zip.closeEntry()
}

/**
 * 把当前 zip 条目解压到 `rootDir` 下的对应路径，并做路径穿越防护。
 *
 * 防护规则：目标文件的 canonical 路径必须位于 `rootDir.canonicalPath + 分隔符` 之下，
 * 否则跳过该条目（恶意 zip 里的 `../../` 会被规范化后落到目录外）。
 *
 * @param zip 当前已定位到某个条目的 zip 流
 * @param rootDir 允许写入的根目录（外置数据目录 或 filesDir）
 * @param entryName 条目名
 * @param isDirectory 条目是否为目录（只创建目录，不写内容）
 */
private fun extractEntry(
    zip: ZipInputStream,
    rootDir: File,
    entryName: String,
    isDirectory: Boolean,
) {
    val root = rootDir.canonicalFile
    val target = File(root, entryName).canonicalFile
    if (!target.path.startsWith(root.path + File.separator)) {
        Log.w(TAG, "跳过越界条目: $entryName")
        return
    }
    if (isDirectory) {
        target.mkdirs()
        return
    }
    target.parentFile?.mkdirs()
    FileOutputStream(target).use { output -> zip.copyTo(output) }
}

/**
 * 删除文件本身及其 SQLite 伴随文件（-wal / -shm / -journal）。
 *
 * @param file 目标文件（本身不存在也不会报错）
 */
private fun deleteWithSidecars(file: File) {
    if (file.exists() && !file.delete()) Log.w(TAG, "删除失败: ${file.absolutePath}")
    deleteSidecars(file)
}

/**
 * 只删除 SQLite 的伴随文件，保留文件本体。
 *
 * @param file 数据库文件
 */
private fun deleteSidecars(file: File) {
    listOf("-wal", "-shm", "-journal").forEach { suffix ->
        val f = File(file.path + suffix)
        if (f.exists() && !f.delete()) Log.w(TAG, "删除失败: ${f.absolutePath}")
    }
}

/**
 * 修正暂存库中的绝对路径，使备份里的数据在新设备/重装后仍能打开。
 *
 * 处理的字段：
 * - `stickers.file_path`：任意旧前缀的 `/stickers/<rest>` → `<ext>/stickers/<rest>`
 * - `attachments.file_path`：任意旧前缀的 `/attachments/<rest>` → `<filesDir>/attachments/<rest>`
 *   （旧版导入曾把附件指到外置目录，这里一并纠正）
 * - `settings.companion_avatar_uri`：`file://.../avatar/<rest>` → `<ext>/avatar/<rest>`
 *
 * 所有路径都通过绑定参数写入，避免路径里的引号破坏 SQL。
 *
 * @param staging 暂存库文件
 * @param extDir 外置数据目录
 * @param attachmentsDir 内部附件目录
 */
private fun rewriteStagedDatabasePaths(staging: File, extDir: File, attachmentsDir: File) {
    if (!staging.exists()) return
    val db = SQLiteDatabase.openDatabase(staging.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    try {
        // 10 = "/stickers/" 的长度：SUBSTR 从标记之后开始，即原路径里的相对部分
        db.execSQL(
            "UPDATE stickers SET file_path = ? || SUBSTR(file_path, INSTR(file_path, '/stickers/') + 10) " +
                "WHERE file_path LIKE '%/stickers/%'",
            arrayOf(File(extDir, STICKERS_DIR_NAME).absolutePath + "/"),
        )
        // 13 = "/attachments/" 的长度
        db.execSQL(
            "UPDATE attachments SET file_path = ? || SUBSTR(file_path, INSTR(file_path, '/attachments/') + 13) " +
                "WHERE file_path LIKE '%/attachments/%'",
            arrayOf(attachmentsDir.absolutePath + "/"),
        )

        // 头像：只有 file:// 且带 /avatar/ 标记的才需要重写
        val marker = "/$AVATAR_DIR_NAME/"
        val current = db.rawQuery(
            "SELECT value FROM settings WHERE key = ?",
            arrayOf("companion_avatar_uri"),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        // Uri.path 返回已解码的路径，避免对 %20 之类的转义做二次编码
        val decodedPath = current?.let { Uri.parse(it) }?.takeIf { it.scheme == "file" }?.path
        val index = decodedPath?.indexOf(marker) ?: -1
        if (decodedPath != null && index >= 0) {
            val rest = decodedPath.substring(index + marker.length)
            val newUri = Uri.fromFile(File(File(extDir, AVATAR_DIR_NAME), rest)).toString()
            db.execSQL(
                "UPDATE settings SET value = ? WHERE key = ?",
                arrayOf(newUri, "companion_avatar_uri"),
            )
        }
    } finally {
        // 关闭连接后 SQLite 会把日志内容落盘；残留的空壳文件在 rename 后会变成孤儿
        db.close()
        deleteSidecars(staging)
    }
}

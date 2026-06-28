package dev.mokouliszt.overlayai

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Claude WEB版に近いスキル機構の最小実装。
 *  - .zip / .skill をアップロード → 解凍して SKILL.md を探す
 *  - SKILL.md の frontmatter(name/description) を解析して永続保存(filesDir/skills)
 *  - チャット時に workspace へ複製し、manifest(一覧)を instructions へ注入
 *  - 実行は shell ツール任せ（Android の toybox 制限に従う：python/node 等は不可）
 */
class SkillManager(private val filesDir: File) {

    val root: File = File(filesDir, "skills").apply { mkdirs() }

    data class Skill(val name: String, val description: String, val dir: File)

    fun list(): List<Skill> =
        root.listFiles { f -> f.isDirectory }?.mapNotNull { dir ->
            val md = File(dir, "SKILL.md")
            if (!md.exists()) return@mapNotNull null
            val (name, desc) = parseFrontmatter(md)
            Skill(name ?: dir.name, desc ?: "", dir)
        }?.sortedBy { it.name } ?: emptyList()

    /** zip/skill の InputStream をインストール。SKILL.md が無ければ例外。 */
    fun install(input: InputStream): Skill {
        val tmp = File(filesDir, "skill_tmp_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            unzip(input, tmp)
            val md = findSkillMd(tmp) ?: error("SKILL.md が見つかりません")
            val srcDir = md.parentFile ?: error("不正な構造です")
            val (name, desc) = parseFrontmatter(md)
            val safe = sanitize(name ?: srcDir.name)
            val dest = File(root, safe)
            if (dest.exists()) dest.deleteRecursively()
            srcDir.copyRecursively(dest, overwrite = true)
            return Skill(name ?: safe, desc ?: "", dest)
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun delete(dirName: String) {
        File(root, dirName).deleteRecursively()
    }

    /** workspace/skills に複製（既に反映済みなら省略）。 */
    fun copyInto(workspace: File) {
        val skills = list()
        val target = File(workspace, "skills")
        if (target.exists() && (target.listFiles()?.size ?: 0) == skills.size) return
        target.deleteRecursively(); target.mkdirs()
        skills.forEach { it.dir.copyRecursively(File(target, it.dir.name), overwrite = true) }
    }

    /** instructions に差し込むスキル一覧（Codex の「## Skills」ディスカバリ書式に準拠）。 */
    fun manifest(): String {
        val skills = list()
        if (skills.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n## Skills\n")
        sb.append(
            "The skills below are available (name + description + file path). Their bodies live on disk " +
                "at the listed paths in the working directory; treat those files as the source of truth.\n" +
                "Trigger: use a skill when the user names it or the task clearly matches its description " +
                "(the description is the primary trigger signal); don't carry a skill across turns unless re-mentioned.\n" +
                "How to use (progressive disclosure):\n" +
                "1. After choosing a skill, read its SKILL.md (e.g. `cat ./skills/<dir>/SKILL.md`) — only enough to follow the workflow.\n" +
                "2. If SKILL.md points to extra folders (references/), open only the specific files you need, not everything.\n" +
                "3. If scripts/ exist, prefer running or patching them over retyping code.\n" +
                "4. If templates/ or assets/ exist, reuse them instead of recreating.\n" +
                "Paths inside SKILL.md are relative to that skill's folder; each shell command starts at the working-directory " +
                "root, so reference bundled files as ./skills/<dir>/<path> (or `cd ./skills/<dir> && ...` within one command).\n\n"
        )
        sb.append("Available skills:\n")
        skills.forEach { s ->
            val desc = s.description.take(300)
            sb.append("- ${s.name}: $desc (file: ./skills/${s.dir.name}/SKILL.md)\n")
        }
        sb.append(
            "\nEnvironment: minimal Android shell (toybox; ls, cat, echo, mkdir, sed, grep, ...). " +
                "python/node and most interpreters are unavailable, so skill steps that require them cannot run here — " +
                "do what the shell allows and clearly tell the user which steps are unsupported on this device.\n"
        )
        // プロンプトを圧迫しないよう上限を設ける（Codex 同様、過大なら切り詰める）
        val out = sb.toString()
        return if (out.length > 7000) out.take(7000) + "\n…(skill list truncated)\n" else out
    }

    // ---- helpers ----

    private fun unzip(input: InputStream, destDir: File) {
        val canonicalRoot = destDir.canonicalPath
        ZipInputStream(BufferedInputStream(input)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(destDir, entry.name)
                // zip-slip 対策
                if (!out.canonicalPath.startsWith(canonicalRoot + File.separator) &&
                    out.canonicalPath != canonicalRoot
                ) throw SecurityException("不正なパス: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zin.copyTo(it) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    private fun findSkillMd(dir: File): File? {
        // 複数ある場合は最も浅い SKILL.md をスキルのルートとみなす
        return dir.walkTopDown()
            .filter { it.isFile && it.name == "SKILL.md" }
            .minByOrNull { it.absolutePath.count { c -> c == File.separatorChar } }
    }

    private fun parseFrontmatter(md: File): Pair<String?, String?> {
        val lines = runCatching { md.readText() }.getOrDefault("").lineSequence().toList()
        if (lines.firstOrNull()?.trim() != "---") return null to null
        var name: String? = null
        var desc: String? = null
        for (i in 1 until lines.size) {
            val l = lines[i]
            if (l.trim() == "---") break
            val idx = l.indexOf(':')
            if (idx <= 0) continue
            val key = l.substring(0, idx).trim()
            val value = l.substring(idx + 1).trim().trim('"', '\'')
            when (key) {
                "name" -> name = value
                "description" -> desc = value
            }
        }
        return name to desc
    }

    private fun sanitize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').take(60).ifBlank { "skill" }
}

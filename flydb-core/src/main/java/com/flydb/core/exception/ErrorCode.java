package com.flydb.core.exception;

/**
 * Flydb 稳定错误码目录（设计 02 §9、06 §5）。
 *
 * <p>错误码是产品对外的稳定契约：CLI 按退出码分支、CI 按码重试、用户按码检索修复建议。
 * 码值分段：{@code 1xxx} 连接与探测、{@code 2xxx} 迁移与校验、{@code 3xxx} 并发锁、{@code 4xxx} 配置。
 * 任何码值或文案变更都需回到设计评审——不可随意改动已有常量。
 *
 * <p>每个码携带：稳定码串、中英文简述、可能原因、建议操作。{@link FlydbException#getMessage()}
 * 将这些组装成固定结构的多行文案，供 CLI 直接渲染。
 */
public enum ErrorCode {

    // ---------------- 1xxx 连接与探测 ----------------
    CONNECT_FAILED("FLYDB-1001", "连接失败", "Connection failed",
            "JDBC URL/账号/密码不正确；数据库未启动；或数据库网络不可达。",
            "核对 flydb.conf 中 flydb.url/flydb.user/flydb.password，并确认数据库进程、地址、端口和网络策略。"),
    UNRECOGNIZED_DATABASE_TYPE("FLYDB-1002", "无法识别数据库类型", "Unrecognized database type",
            "JDBC URL 前缀无法映射到已知方言；或数据库产品名探测结果不在支持矩阵内。",
            "用 --database-type 显式指定方言（见支持矩阵）；或检查 URL 前缀拼写。"),
    DRIVER_NOT_FOUND("FLYDB-1003", "JDBC 驱动未找到", "JDBC driver not found",
            "本地来源没有对应驱动；驱动坐标/类名有误；或 Maven 私服认证、代理和网络失败。",
            "按详情中的解析轨迹修正 Maven settings/坐标，或将驱动 jar 放入提示的 <安装目录>/drivers/。"),

    // ---------------- 2xxx 迁移与校验 ----------------
    INVALID_VERSION("FLYDB-2001", "非法版本号", "Invalid version number",
            "迁移脚本版本号未以数字开头，含空段/非法字符，或文件名不符合版本化迁移规范。",
            "使用 V<版本>__<描述>.sql；版本可含字母数字段，并用点、下划线或连字符分隔。"),
    DUPLICATE_VERSION("FLYDB-2002", "重复版本", "Duplicate version",
            "两个或多个迁移脚本解析出相同的版本号。",
            "为冲突脚本分配互不相同的版本号（详情列出冲突文件）。"),
    CHECKSUM_MISMATCH("FLYDB-2003", "校验和不匹配", "Checksum mismatch",
            "本地脚本内容与历史表记录的 checksum 不一致——脚本在应用后被改动。",
            "若改动是预期的，执行 flydb repair 对齐 checksum；否则从版本控制还原脚本。"),
    FAILED_MIGRATION_NEEDS_REPAIR("FLYDB-2004", "存在失败记录需 repair", "Failed migration needs repair",
            "历史表中存在 success=false 的记录，阻塞后续 migrate。",
            "修正失败脚本后执行 flydb repair 清除失败记录，再重跑 migrate。"),
    LEGACY_R_PREFIX_NAMING("FLYDB-2005", "旧式 R 前缀命名", "Legacy R-prefix naming",
            "扫描到 R<数字>__ 形式的旧式命名。2.0 起 R 表示「可重复迁移」且不带版本号——继续兼容会造成数据灾难。",
            "回退脚本改名为 U<版本>__；可重复脚本改名为 R__（不带版本号）。该检查不可关闭。"),
    OUT_OF_ORDER_MIGRATION("FLYDB-2006", "乱序迁移", "Out-of-order migration",
            "存在版本号低于已应用最高版本的未执行迁移，且 flydb.out-of-order=false。",
            "确认要补执行旧版本迁移时，设置 flydb.out-of-order=true；否则按版本顺序补齐脚本。"),
    BASELINE_PRECONDITION_UNMET("FLYDB-2007", "baseline 前置不满足", "Baseline precondition unmet",
            "目标库已存在迁移记录，或 baselineVersion 与现状冲突。",
            "对一个已存在历史表的库，先 repair/清理或在空库上执行 baseline。"),
    MISSING_UNDO_SCRIPT("FLYDB-2008", "缺少 undo 脚本", "Missing undo script",
            "执行 undo 时未找到与最近一次版本化迁移对应的 U<版本>__ 脚本。",
            "补一个 U<版本>__<描述>.sql 脚本以撤销对应 V 迁移。"),
    UNDEFINED_PLACEHOLDER("FLYDB-2009", "未定义占位符", "Undefined placeholder",
            "脚本中引用了未在 flydb.placeholders.* 配置的占位符。",
            "在 flydb.conf 补充该占位符的值，或修正脚本中的引用（详情含占位符与行号）。"),
    MIGRATION_EXECUTION_FAILED("FLYDB-2010", "迁移执行失败", "Migration execution failed",
            "迁移脚本中的某条 SQL 语句执行时数据库返回错误。",
            "根据详情中的脚本名、语句序号与起始行号定位语句，修正脚本后重跑 migrate（详情含驱动原始错误）。"),

    // ---------------- 3xxx 并发锁 ----------------
    LOCK_ACQUISITION_TIMEOUT("FLYDB-3001", "获取迁移锁超时", "Lock acquisition timed out",
            "另一个 flydb 进程正在对该数据库执行迁移；或前次迁移进程异常终止后锁尚未释放。",
            "用 flydb info 查看锁状态；确认无并发迁移后重试，或调大 flydb.lock-timeout-seconds。"),

    // ---------------- 4xxx 配置 ----------------
    UNKNOWN_CONFIG_KEY("FLYDB-4001", "未知配置键", "Unknown config key",
            "配置文件中出现无法识别的 flydb.* 键（可能拼写错误或属未来版本）。",
            "删除或修正该键（详情列出未知键与近似建议）；完整配置项见参考文档。"),
    MISSING_REQUIRED_CONFIG("FLYDB-4002", "缺少必填配置项", "Missing required config",
            "未提供必填的 flydb.url（且未通过 --url / 环境变量提供）。",
            "在 flydb.conf、命令行或环境变量 FLYDB_URL 中提供 JDBC URL（详情列出缺失项）。"),
    CLEAN_DISABLED("FLYDB-4003", "clean 被禁用", "Clean disabled",
            "flydb.clean-disabled=true（默认防呆），clean 命令被拒绝执行。",
            "确认要清空目标 schema 后，设置 flydb.clean-disabled=false，并在交互终端或加 --force 二次确认。"),
    INIT_TARGET_EXISTS("FLYDB-4004", "目标文件已存在，拒绝覆盖", "Init target already exists",
            "init 生成的 flydb.conf 或首个迁移脚本已存在；Flydb 不会覆盖已有文件。",
            "选择空目录执行 init，或先备份并移走冲突文件后重试。");

    private final String code;
    private final String zhSummary;
    private final String enSummary;
    private final String zhCause;
    private final String zhAction;

    ErrorCode(String code, String zhSummary, String enSummary, String zhCause, String zhAction) {
        this.code = code;
        this.zhSummary = zhSummary;
        this.enSummary = enSummary;
        this.zhCause = zhCause;
        this.zhAction = zhAction;
    }

    public String code() {
        return code;
    }

    public String zhSummary() {
        return zhSummary;
    }

    public String enSummary() {
        return enSummary;
    }

    public String zhCause() {
        return zhCause;
    }

    public String zhAction() {
        return zhAction;
    }
}

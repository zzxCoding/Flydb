import type { ConfigSnapshot } from './types'

// 这里只维护表单用语。可用键、默认值以及生效来源均由运行时提供。
const sections = {
  connection: [
    ['url', '数据库连接地址', 'JDBC URL',
      '填目标库的完整 JDBC 地址。主机、端口和库名由数据库管理员提供。', 'Enter the JDBC URL for the target database, using its host, port and database name.', 'jdbc:mysql://localhost:3306/orders'],
    ['user', '数据库用户', 'Database user',
      '填登录目标数据库的账号。环境使用不同账号时，在各自配置中覆盖。', 'Enter the account used to connect to this database. Override it per environment if accounts differ.', 'orders_deploy'],
    ['password', '数据库密码或环境变量引用', 'Database password or environment reference',
      '共享配置中可引用环境变量。先在运行 Flydb 的进程中设置该变量，再填写引用。', 'Set the variable in the environment of the Flydb process, then enter the reference here.', '${env:DB_PASSWORD}'],
    ['password.file', '密码文件', 'Password file',
      '用文件提供密码时，只需填文件路径。Flydb 从执行机器读取文件，相对路径从工作目录算起。', 'Enter the path to the password file on the machine running Flydb. Relative paths start at the working directory.', '/run/secrets/orders_db_password'],
    ['config-import', '导入共享配置', 'Import shared configuration',
      '填一份共享 .conf 文件的路径。相对路径从当前配置文件所在目录算起，同名设置可在本文件覆盖。', 'Enter one shared .conf file. Relative paths start at this file’s directory. Local settings override imported ones.', '../shared/flydb.conf'],
  ],
  drivers: [
    ['database-type', '数据库方言', 'Database dialect',
      '通常由数据库自动探测。需要指定方言时填方言 ID，也可以填已安装扩展的 ID。', 'Usually detected from the database. To force a dialect, enter its ID, including IDs supplied by installed extensions.', 'mysql'],
    ['driver', 'JDBC 驱动类', 'JDBC driver class',
      '需要指定 JDBC 驱动时，填驱动文档里的 Java 类全名。', 'To select a JDBC driver explicitly, enter the fully qualified Java class name from its documentation.', 'com.mysql.cj.jdbc.Driver'],
    ['driver-coordinate', '驱动 Maven 坐标', 'Driver Maven coordinate',
      '需要从 Maven 仓库解析指定驱动时，依次填组名、制品名、版本，用冒号隔开。', 'To resolve a specific driver from Maven, enter its group ID, artifact ID and version separated by colons.', 'com.mysql:mysql-connector-j:8.2.0'],
    ['driver-download', '驱动下载策略', 'Driver download policy',
      '决定本地找不到驱动时，能否从 Maven 仓库下载。离线模式始终禁止远程解析。', 'Controls whether Flydb may download a missing driver from Maven. Offline mode always prevents remote resolution.', 'auto'],
    ['driver-cache', '驱动缓存目录', 'Driver cache directory',
      '填用于缓存下载驱动的可写目录。使用默认缓存时可省略。', 'Enter a writable directory for downloaded drivers. Omit to use the default driver cache.', './cache/jdbc'],
    ['maven-settings', 'Maven 设置文件', 'Maven settings file',
      '使用公司镜像、私服或代理时，填 Flydb 可读取的 Maven settings.xml 文件路径。', 'Enter the path to a readable Maven settings.xml to use your organization’s mirror, repository or proxy.', '/opt/maven/conf/settings.xml'],
    ['maven-local-repository', 'Maven 本地仓库', 'Local Maven repository',
      '已有 Maven 本地仓库时，填包含驱动制品的仓库根目录。', 'Enter the root directory of the local Maven repository containing the driver artifacts.', '/opt/maven/repository'],
    ['offline', '离线模式（仅使用本地驱动）', 'Offline mode (local drivers only)',
      '选择是否只从本机寻找 JDBC 驱动。内网离线执行前，先把驱动放到本地。', 'Choose whether to resolve JDBC drivers only from local sources. Make drivers available locally before running offline.', 'true'],
  ],
  scripts: [
    ['locations', '迁移脚本目录', 'Migration script locations',
      '填脚本目录，多个用英文逗号分隔。filesystem: 相对路径从工作目录算起，会递归扫描子目录。', 'Separate locations with commas. Relative filesystem: paths start at the working directory. Subdirectories are scanned recursively.', 'filesystem:db/migration,filesystem:db/shared'],
    ['encoding', 'SQL 文件编码', 'SQL file encoding',
      '按 SQL 文件实际保存时的编码填写 Java 字符集名称。同一配置下的脚本使用同一编码。', 'Enter the Java charset matching the encoding of the SQL files. It applies to all scripts in these locations.', 'UTF-8'],
    ['sql-migration-prefix', '版本化脚本名前缀', 'Versioned script prefix',
      '填版本号之前的字符。脚本名 V2__add_note.sql 的前缀是 V。', 'Enter the characters before the version. In V2__add_note.sql the prefix is V.', 'V'],
    ['repeatable-migration-prefix', '可重复脚本名前缀', 'Repeatable script prefix',
      '填可重复脚本名的起始字符。R__views.sql 用 R 标记，内容变化后可再次执行。', 'Enter the prefix for repeatable scripts. R__views.sql uses R and can run again when its content changes.', 'R'],
    ['undo-migration-prefix', '撤销脚本名前缀', 'Undo script prefix',
      '填撤销脚本名中版本号之前的字符。U2__add_note.sql 对应版本 2。', 'Enter the prefix before the undo script’s version. U2__add_note.sql corresponds to version 2.', 'U'],
    ['sql-migration-separator', '版本号与描述的分隔符', 'Separator between version and description',
      '填版本号与描述之间的字符。V2__add_note.sql 中间是两个下划线。', 'Enter the characters between version and description. V2__add_note.sql uses two underscores.', '__'],
    ['sql-migration-suffix', 'SQL 脚本扩展名', 'SQL script suffix',
      '填 Flydb 要识别的文件名结尾，通常包含扩展名前的点。', 'Enter the filename ending to recognize, including the dot for a normal extension.', '.sql'],
    ['directory-glob', '按父目录通配符筛选', 'Filter parent directories by glob',
      '按脚本根目录下的相对父目录筛选。** 可跨目录，与父目录正则二选一。', 'Match the parent directory relative to the script root. ** spans directories. Choose this or the directory regex.', 'orders/**'],
    ['file-glob', '按文件名通配符筛选', 'Filter filenames by glob',
      '只匹配文件名。* 匹配任意多个字符，? 匹配一个字符。与文件名正则二选一。', 'Match just the filename. * matches any number of characters and ? matches one. Choose this or the filename regex.', 'V*__*.sql'],
    ['path-glob', '按完整路径通配符筛选', 'Filter paths by glob',
      '匹配含文件名的相对路径。用 / 分层、** 跨目录，与完整路径正则二选一。', 'Match the relative path including the filename. Use / for separators and ** across directories. Choose this or the path regex.', 'orders/**/*.sql'],
    ['directory-regex', '按父目录正则筛选', 'Filter parent directories by regex',
      '用 Java 正则匹配整个相对父目录。路径用 /，与父目录通配符二选一。', 'Match the entire relative parent directory with a Java regex using / separators. Choose this or the directory glob.', 'orders/(qa|dev)'],
    ['file-regex', '按文件名正则筛选', 'Filter filenames by regex',
      '用 Java 正则匹配整个文件名。与文件名通配符二选一。', 'Match the entire filename with a Java regex. Choose this or the filename glob.', 'V[0-9]+__.*[.]sql'],
    ['path-regex', '按完整路径正则筛选', 'Filter paths by regex',
      '用 Java 正则匹配含文件名的整个相对路径。与完整路径通配符二选一。', 'Match the entire relative path including the filename with a Java regex. Choose this or the path glob.', 'orders/.*[.]sql'],
  ],
  versions: [
    ['target-version', '目标版本', 'Target version',
      '填脚本里的版本号，省略 V。精确模式只选该版本，版本族模式还选它的子版本。', 'Enter the version without the V prefix. Exact mode selects that version. Family mode includes its descendants.', '2.1'],
    ['start-version', '起始版本', 'Start version',
      '填范围内最早的版本，包含该版本。只设起点时，之后的版本都在范围内。', 'Enter the first version to include. With only a start bound, later versions remain in scope.', '2'],
    ['end-version', '结束版本', 'End version',
      '填范围内最后的版本。例如结束版本为 3 时，普通范围排除 3.1，版本族范围包含 3.1。', 'Enter the last version to include. With an end of 3, range excludes 3.1 while family-range includes it.', '3'],
    ['version-selection', '版本选择方式', 'Version selection mode',
      '按发布范围选择。未指定时，有目标版本就精确匹配，有起止版本就按范围，两者都没有则不筛选。', 'Choose the release scope. When omitted, a target implies exact, bounds imply range, and neither leaves versions unrestricted.', 'family-range'],
    ['version-source', '版本号来源（文件名或目录）', 'Version source (file or directory)',
      '选择筛选时读取哪里的版本号。用目录版本时，文件版本必须属于对应目录的版本族。', 'Choose where version selection reads the version. For directory versions, each file version must belong to its directory’s family.', 'file'],
    ['version-regex', '选择版本的正则表达式', 'Version selection regex',
      '配合 regex 模式，用 Java 正则匹配整个规范化版本号。例如匹配 2.1、2.2。', 'Use with regex mode. Match the entire normalized version. This example matches 2.1 and 2.2.', '2[.][0-9]+'],
    ['migration-order', '脚本执行顺序', 'Migration order',
      '按目录版本排序时，先比较目录版本，再比较文件版本。请选择与脚本组织方式一致的顺序。', 'Directory ordering compares the directory version first and then the file version. Choose the order matching your script layout.', 'version'],
    ['directory-version-regex', '从目录提取版本号的正则', 'Extract directory versions by regex',
      '目录名含文字时，用 version 命名组或第一个捕获组提取版本号。例如从 release-23 提取 23。', 'For directory names with text, capture the version in a group named version or the first capturing group. This extracts 23 from release-23.', '(?:^|/)release-(?<version>[0-9]+)(?=$|/)'],
  ],
  execution: [
    ['table', '迁移历史表名', 'Migration history table',
      '填记录脚本执行历史的表名。接入已有项目时沿用原表名，以便读取已有记录。', 'Enter the table that stores migration history. Keep the existing name when connecting to an established project.', 'flydb_schema_history'],
    ['baseline-version', '基线版本号', 'Baseline version',
      '已有库首次建基线时，填库里已经具备的版本。该版本及以下的版本化脚本会被基线覆盖。', 'For an existing database, enter the version already represented in its schema. Versioned scripts at or below this baseline are covered.', '1'],
    ['baseline-on-migrate', '首次迁移时自动建立基线', 'Baseline on first migration',
      '非空库首次接入且没有历史表时，是否在迁移前按基线版本建立记录。', 'Choose whether the first migration of a nonempty database without a history table should create a baseline first.', 'false'],
    ['validate-on-migrate', '迁移前校验', 'Validate before migration',
      '选择执行迁移前是否自动核对本地脚本与数据库迁移历史。', 'Choose whether migration automatically checks local scripts against the database history first.', 'true'],
    ['out-of-order', '允许乱序补执行低版本', 'Allow out-of-order migrations',
      '数据库已到 V3 时，如果后来补入 V2，开启后允许补执行这个较低版本。', 'If the database is at V3 and V2 arrives later, enabling this setting allows the older migration to run.', 'false'],
    ['clean-disabled', '禁用数据库清理', 'Disable database clean',
      'true 禁止执行 clean 清库。选 false 后，仍需单独执行 clean 才会清理数据库。', 'True blocks the clean command. With false, cleaning the database still requires explicitly running clean.', 'true'],
    ['lock-timeout-seconds', '迁移锁超时（等待秒数）', 'Migration lock timeout in seconds',
      '填等待迁移锁的秒数，使用大于或等于 0 的整数。', 'Enter the number of seconds to wait for the migration lock, as a whole number of 0 or greater.', '60'],
    ['batch-size', 'SQL 批量执行大小（每批语句数）', 'SQL batch size (statements per batch)',
      '填每批 SQL 的语句数，最小为 1。填 1 逐条执行，填 20 表示每批最多 20 条。', 'Enter the statements per batch, at least 1. Use 1 to execute individually or 20 for up to 20 statements per batch.', '20'],
    ['callbacks', '迁移回调类', 'Migration callback classes',
      '填已加入运行时类路径的 Java 回调实现类全名。多个类用英文逗号分隔。', 'Enter fully qualified Java callback classes available on the runtime classpath, separated by commas.', 'com.example.flydb.AuditCallback'],
  ],
  placeholders: [
    ['placeholder-replacement', '启用占位符替换', 'Enable placeholder replacement',
      '开启后，用配置中的值替换 SQL 占位符。SQL 需要保留模板原文时可关闭。', 'Enable to substitute configured values for SQL placeholders. Disable to preserve the template text in SQL.', 'true'],
    ['placeholder-prefix', '占位符前缀', 'Placeholder prefix',
      '填占位符名称左侧的标记。例如 SQL 写作 ${schema} 时，开头是 ${。', 'Enter the opening delimiter. For ${schema} in SQL, the opening delimiter is ${.', '${'],
    ['placeholder-suffix', '占位符后缀', 'Placeholder suffix',
      '填占位符名称右侧的标记。例如 SQL 写作 ${schema} 时，结尾是 }。', 'Enter the closing delimiter. For ${schema} in SQL, the closing delimiter is }.', '}'],
  ],
} as const

interface ConfigLabel { section: string; zh: string; en: string; hintZh: string; hintEn: string; example: string }
const labels = new Map<string, ConfigLabel>()
for (const [section, entries] of Object.entries(sections)) {
  for (const [key, zh, en, hintZh, hintEn, example] of entries) labels.set('flydb.' + key, { section, zh, en, hintZh, hintEn, example })
}
export const configSections = [...Object.keys(sections), 'other']
// 选择项说明保留配置文件中的原始拼写，供表单选择和文件编辑器共用。
type Option = readonly [value: string, zh: string, en: string]
const options: Record<string, readonly Option[]> = {
  'flydb.driver-download': [
    ['auto', '本地缺失时允许下载', 'Download if missing locally'],
    ['never', '禁止下载', 'Never download'],
  ],
  'flydb.version-selection': [
    ['exact', '精确匹配目标版本', 'Match the target version'],
    ['range', '按起止版本筛选，至少填一端', 'Use start or end bounds, or both'],
    ['family', '包含目标版本及其子版本', 'Include the target and its descendants'],
    ['family-range', '填起始或结束版本，含结束版本的子版本', 'Use start or end bounds, including descendants of the end version'],
    ['regex', '按版本正则表达式筛选', 'Use the version selection regex'],
  ],
  'flydb.version-source': [
    ['file', '取文件名中的版本', 'Read the filename version'],
    ['directory', '取父目录中的版本', 'Read the directory version'],
  ],
  'flydb.migration-order': [
    ['version', '按文件版本排序', 'Sort by file version'],
    ['directory-version', '先目录版本，再文件版本', 'Sort by directory version, then file version'],
  ],
  'flydb.clean-disabled': [
    ['true', '禁止清库', 'Block clean'], ['false', '允许执行清库命令', 'Permit clean'],
  ],
}
const switches: readonly Option[] = [['true', '开启', 'On'], ['false', '关闭', 'Off']]
export function configOptions(key: string, defaults?: Record<string, string>): readonly Option[] {
  return options[key] ?? (['true', 'false'].includes(defaults?.[key] ?? '') ? switches : [])
}
export function configLabel(key: string): ConfigLabel {
  const name = key.replace(/^flydb\.placeholders\./, '')
  if (name !== key) return { section: 'placeholders', zh: '占位符 ' + name, en: 'Placeholder ' + name,
    hintZh: '填写 SQL 中此占位符要替换成的文本。名称须与脚本一致。', hintEn: 'Enter the text to substitute for this placeholder. Its name must match the SQL script.', example: name === 'schema' ? 'orders' : '' }
  return labels.get(key) ?? { section: 'other', zh: key, en: key, hintZh: '', hintEn: '', example: '' }
}

export function configSource(snapshot: ConfigSnapshot | undefined, key: string, t: (key: string) => string) {
  const value = snapshot?.sources[key]
  if (!value && key === 'flydb.config-import' && snapshot?.values[key]) return t('sources.FILE')
  if (!value) return ''
  const [kind, ...detail] = value.split(':')
  const origin = snapshot?.declaredAt?.[key]
  if (origin && kind !== 'FILE' && !detail.includes(origin)) detail.push(origin)
  return [t('sources.' + kind), ...detail].join(' · ')
}

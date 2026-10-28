UPDATE product_releases SET current=false WHERE current;

INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000022',
    '2026.08.26.1',
    'Android 全功能原生管理版',
    '英格云 Android 升级到 0.2.0：组织管理员可在员工模式与管理模式间切换，管理流程、经营看板和员工业务缺口均以原生页面提供。',
    '{"新增":["组织管理员新增员工模式与管理模式切换，以及管理概览、成员团队、业务治理、经营看板和管理更多五项原生导航","新增原生管理待办聚合、成员团队管理、成单审核、绩效审核、字典、AI 配置、审计、Webhook 和资料补全入口","新增手机竖屏经营看板，展示重点提示、客户指标、A 类超时、院校对比、趋势和排行榜"],"改进":["客户筛选、协作者、分级、归属期限、时间线、提醒、团队数据、通知、绩效、内容和智能助手能力补齐到 Android 原生端","移动启动信息改为服务端能力清单裁剪，管理员入口不再仅凭本地角色字符串判断","文件上传、下载、分享和长内容展开统一使用 Android 原生能力"],"修复":["修复 Release 网络请求、JSON 混淆、HTTP 精确主机放行和包信息校验问题","继续隐藏已退役的业务周期和成交预测写入口，成单保持员工申请、管理员确认的极简流程"],"安全":["除 QQ 工作台外 Release 包禁止其他 WebView，管理接口继续执行角色、组织、版本和幂等校验","AI 密钥只允许单向录入且不回显、不缓存，危险操作保留二次确认和审计"]}'::jsonb,
    '2026-08-26T18:00:00+08:00',
    true,
    true
);

INSERT INTO mobile_app_releases(
    id,
    platform,
    version_name,
    version_code,
    minimum_supported_version_code,
    mandatory,
    notes,
    apk_path,
    apk_size,
    apk_sha256,
    manifest_signature,
    published,
    released_at
) VALUES (
    '4d892d74-84e0-47ae-8508-2cdf8061dc42',
    'ANDROID',
    '0.2.0',
    5,
    1,
    false,
    '新增员工/管理双模式、五项原生管理导航、手机经营看板及完整员工业务能力；QQ 仍为唯一受控 WebView。',
    'android/yingge-cloud-0.2.0.apk',
    5189611,
    '6ffba1b0b8336ef146eb9f8f8e96456ef4b1d7bf1ad796aec89199ccedab2a62',
    null,
    true,
    '2026-08-26T18:00:00+08:00'
)
ON CONFLICT (platform, version_code) DO UPDATE SET
    version_name = excluded.version_name,
    minimum_supported_version_code = excluded.minimum_supported_version_code,
    mandatory = excluded.mandatory,
    notes = excluded.notes,
    apk_path = excluded.apk_path,
    apk_size = excluded.apk_size,
    apk_sha256 = excluded.apk_sha256,
    manifest_signature = excluded.manifest_signature,
    published = excluded.published,
    released_at = excluded.released_at;

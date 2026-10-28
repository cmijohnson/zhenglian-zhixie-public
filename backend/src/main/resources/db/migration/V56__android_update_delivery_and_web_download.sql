UPDATE product_releases SET current=false WHERE current;

INSERT INTO product_releases (
    id, version, title, summary, sections, released_at, published, current
) VALUES (
    '20000000-0000-0000-0000-000000000027',
    '2026.09.03.2',
    'Android 更新下载与飞书入口修复',
    '修复 Android 安装包下载完成后被误判为网络失败的问题，并在网页版增加 APK 下载和飞书客户表直达入口。',
    '{"新增":["更新日志页面增加当前 Android 安装包下载入口","飞书多维表格管理页增加客户表一键打开入口"],"修复":["Android 安装包下载、校验和安装准备改为后台线程执行，避免完整响应到达后在主线程处理失败","更新下载、校验和系统安装器异常现在返回可定位的错误提示，不再统一显示网络失败"],"安全":["网页下载继续要求已登录账号，安装包仍由服务端校验发布记录、文件大小和 SHA-256","飞书入口仅根据已保存的 Base 和数据表标识构造，不返回 App Secret 或访问令牌"]}'::jsonb,
    '2026-09-03T07:48:00+08:00',
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
    '3ab8c6ee-36e4-4e96-8f01-d91d76513914',
    'ANDROID',
    '0.2.3',
    8,
    1,
    false,
    '修复应用内更新下载完成后仍提示网络失败；新增可定位的下载、校验和安装错误，并提供网页版 APK 直装入口。',
    'android/yingge-cloud-0.2.3.apk',
    5205995,
    'ac1caf1fab523b70fd76e6bbc7f839e1f289ff745a90c87566afad062b25bf3a',
    null,
    true,
    '2026-09-03T07:48:00+08:00'
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

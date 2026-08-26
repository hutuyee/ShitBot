# ShitBot TODO（Agent）

## 添加手动更新（已完成）

- [x] 插件加载时异步访问 GitHub latest release，设置连接/读取超时，不阻塞主线程
- [x] 对比当前版本；远端 Release 信息变化时写入缓存，管理员上线时提示可点击链接
- [x] `/shitbot update` 下载对应平台 JAR，校验 SHA-256、平台、主类和内嵌版本
- [x] 校验通过后备份旧 JAR 并替换，提示管理员手动重启，不执行热重载
- [x] BungeeCord 通过已认证 Console Socket 联动所有已配置 Spigot 后端

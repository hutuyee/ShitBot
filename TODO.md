# ShitBot TODO（Agent）

## 添加手动更新（已完成）

- [x] 插件加载时异步访问 GitHub latest release，设置连接/读取超时，不阻塞主线程
- [x] 对比当前版本；远端 Release 信息变化时写入缓存，管理员上线时提示可点击链接
- [x] 添加 `/shitbot update` 手动检查命令
- [x] 仅提醒更新，不自动下载或替换插件

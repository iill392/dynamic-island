# 游龙灵动岛

Android 灵动岛应用，纯 Java Canvas 实现，包名 `com.lingdong.island`。

## 项目结构

| 路径 | 内容 |
|---|---|
| `app/` | Android 工程，可直接编译 |
| `app/app/src/main/java/com/lingdong/island/` | 全部 Java 源码 |
| `app/app/src/main/res/` | 资源与解锁动画帧 |

## 构建

```bash
cd app
gradlew.bat :app:assembleDebug
```

或：

```bash
gradle -p app :app:assembleDebug
```

产物：`app/app/build/outputs/apk/debug/app-debug.apk`

## 使用

安装后按引导完成 5 项必需权限，然后打开主页的灵动岛开关。

## 许可证

GNU Affero General Public License v3.0（AGPL-3.0），附加使用条款已写入根目录 `LICENSE` 文件。

仓库：https://github.com/iill392/dynamic-island

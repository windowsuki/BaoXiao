# 固定签名与一次迁移

0.1.2-final 原私钥丢失，用户已接受一次完整备份迁移。0.1.3 为永久本地 release 签名的起点，后续不能重新生成密钥。

## 文件位置

- 公开指纹与包名：`config/release-signing.json`，随源码提交。
- 私钥：`signing/baoxiao-release.p12`；口令：`signing/keystore-password.txt`。整个 signing/ 被 Git 忽略。
- 独立私密备份：`C:/Users/wuyue/Documents/BaoXiao-signing-backup/BaoXiao-signing-75e46858eb82.zip`。包含密钥和口令，请另存一份到私人备份位置，不要发到公开仓库或构建产物。
- 便携签名工具：`app/build/signing-tools/`；仅 Java 运行时及 apksigner.jar，不安装 Android Studio，不修改系统设置。

固定证书 SHA-256：`75e46858eb823554e1d58978bda3d143b2c3542662e21ee4726efbfcba78445f`，有效期至 2066-10-26。

## 本次手机迁移

1. 在旧版点「更多 → 完整备份」，把 ZIP 保存到应用外（例如下载目录），再复制一份到电脑。不要用「导出全部材料与清单」代替完整备份。
2. 确认 ZIP 已保存，能打开且包含 manifest.json 和原始附件。恢复支持解压总量不超过 1 GiB、10000 个 ZIP 条目、manifest 不超过 10 MiB；超出时先处理备份，不要先卸载。
3. 卸载旧版后安装已核验的 BaoXiao-0.1.3.apk，在新装空库中点「更多 → 恢复备份」导入这份 ZIP。
4. 核对恢复笔数、一笔旧到账记录及图片/PDF原文件，确认无误后继续使用。已取消报账从新统计中排除，金额变化本身不代表丢失数据。

本次没有访问手机数据，也没有执行手机卸载或恢复；真实恢复结果由手机操作核对。

## 以后打包

1. 改功能并提高 app/build.gradle.kts 的 versionCode。
2. 推送开发分支，Actions 执行单元测试、Debug/Release Lint、assembleRelease；云端验证 APK 包名、版本、未签名及 ZIP 对齐后上传 BaoXiao-unsigned。
3. 下载产物并核对 GitHub artifact 摘要与内部 SHA256SUMS。保留 Unsigned/app-release-unsigned.apk 与同目录 build-info.json，把 tools/apksigner.jar 放到本地 app/build/signing-tools/，并保存已核对的 jar 摘要为 apksigner.jar.sha256。
4. 在本机执行（PowerShell 7）：

```powershell
./scripts/Sign-Apk.ps1 -UnsignedApk 'app/build/delivery/版本号/unsigned/Unsigned/app-release-unsigned.apk' -OutputApk 'app/build/delivery/版本号/BaoXiao-版本号.apk'
```

脚本核对输入摘要、包名和版本，读取口令文件而不在命令中展开口令；签完后用 apksigner 完整验证并比较固定证书指纹，生成旁边的 verification.txt。私钥不会发送到云端。

如换电脑，先把备份内的 baoxiao-release.p12 与 keystore-password.txt 恢复到 signing/，保留源码的公开指纹，再运行 Initialize-ReleaseSigning.ps1 -BackupDirectory 外部备份目录检查。只有首次且明确接受换签名迁移时才允许 -Create。后续私钥缺失、文件不全或指纹不符必须停止并恢复备份，不能自动生成新 key。

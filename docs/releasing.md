# 发布 / Releasing

## 构建与验收 / Build and verify

使用 JDK 17 或 21。Windows 将 `./mvnw` 换成 `.\mvnw.cmd`。重新构建前停止 demo，避免 Windows 的 JAR 文件锁。

Use JDK 17 or 21. On Windows, use `.\mvnw.cmd`. Stop the demo before rebuilding to release its JAR file lock.

先生成管理令牌，浏览器验收与 demo 使用同一个 `POOLPILOT_ADMIN_TOKEN`。PowerShell 执行 `$env:POOLPILOT_ADMIN_TOKEN = .\scripts\new-admin-token.ps1`；下面的 Bash 示例需要 OpenSSL。

Generate an admin token first. Use the same `POOLPILOT_ADMIN_TOKEN` for the demo and browser checks. In PowerShell, run `$env:POOLPILOT_ADMIN_TOKEN = .\scripts\new-admin-token.ps1`; the Bash example below requires OpenSSL.

```bash
export POOLPILOT_ADMIN_TOKEN="$(openssl rand -hex 32)"
./mvnw -B -ntp clean verify
java -jar poolpilot-demo/target/poolpilot-demo-0.1.0.jar --poolpilot.persistence.enabled=false
```

在另一终端运行浏览器验收。测试会修改内存配置；持久化应关闭。需要 Node.js 22+，默认使用已安装的 Chrome。

Run browser acceptance in another terminal. Tests change in-memory settings; keep persistence disabled. Node.js 22+ and an installed Chrome are required by default.

```bash
npm ci
npm run test:browser
```

使用 Chromium 时执行 `npx playwright install chromium`，并设置 `POOLPILOT_BROWSER=chromium`。`POOLPILOT_URL` 和 `POOLPILOT_ADMIN_TOKEN` 可指定测试地址和令牌。性能实验单独构建，见 [JMH](../benchmarks/README.md)。

For Chromium, run `npx playwright install chromium` and set `POOLPILOT_BROWSER=chromium`. Set `POOLPILOT_URL` and `POOLPILOT_ADMIN_TOKEN` for another test instance. Build [JMH benchmarks](../benchmarks/README.md) separately.

## 附件 / Artifacts

```bash
python scripts/package-release.py
```

| 文件 / File                       | 内容 / Contents                                                                         |
| --------------------------------- | --------------------------------------------------------------------------------------- |
| `dist/poolpilot-0.1.0-source.zip` | 源码、测试、文档、基准、Wrapper 和 CI / source, tests, docs, benchmarks, Wrapper and CI |
| `dist/poolpilot-demo-0.1.0.jar`   | 可执行 demo / executable demo                                                           |
| `dist/SHA256SUMS.txt`             | SHA-256 校验值 / checksums                                                              |

源码包使用固定时间戳，保留 `mvnw` 的 Unix 执行权限，排除构建输出、Git 元数据、Node 依赖、日志、环境文件和运行状态。发布前解压源码包并运行 `verify`，核对附件校验值。

The source archive uses fixed timestamps and retains Unix execution permissions for `mvnw`. Build output, Git metadata, Node dependencies, logs, environment files and runtime state are excluded. Extract and verify the source archive before release, and check artifact hashes.

## GitHub

维护者在验收通过后推送代码并创建版本标签。已有 Git 仓库可跳过初始化。

Maintainers push verified changes and create a version tag. Skip initialization for an existing Git repository.

```bash
git init -b main
git add .
git update-index --chmod=+x mvnw
git commit -m "Release PoolPilot 0.1.0"
git remote add origin https://github.com/Htd7860/poolpilot.git
git push -u origin main
```

CI 通过后创建 `v0.1.0` 标签和 Release，附上上述三个文件，使用 [CHANGELOG](../CHANGELOG.md) 作为版本说明。Maven Central 发布需另行配置工件签名与仓库元数据。

After CI passes, create tag `v0.1.0` and a release containing the three artifacts. Use the [changelog](../CHANGELOG.md) for release notes. Maven Central publication requires artifact signing and repository metadata configuration.

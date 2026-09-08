# Manga Extensions

面向 Mihon/Tachiyomi/Suwayomi 的扩展源,fork 自 [Keiyoushi Extensions](https://github.com/keiyoushi/extensions-source)。

本仓库只构建和发布 [`.github/extensions.txt`](.github/extensions.txt) 里列出的源,目前是:

* 拷贝漫画 Copy3000 (`zh`) — [`src/zh/copy3000`](src/zh/copy3000)

## 使用方法

在 Mihon/Suwayomi 里用这个 URL 添加仓库:

```
https://github.com/aar0u/manga-extensions/raw/repo/index.pb
```

## 为什么要单独 fork

拷贝漫画的站点经常变,这个源需要按自己的节奏更新。

顺便的好处是,这个仓库的 [`publish-fork.yml`](.github/workflows/publish-fork.yml) 构建出来的 apk 对 Suwayomi 更友好。keiyoushi 原本的构建流程(`assembleRelease` + jar 签名)产出的扩展,有些客户端装不了——尤其是 Suwayomi,它跑在桌面 JVM 上而不是 Android 的 ART,要靠 dex2jar 把安装的 APK 反编译回 JVM class 文件,经常因此报 `VerifyError`。这个仓库改成:

* 每个模块只跑 `generateSourceInfo` + `packageRelease`(只出 apk,不出 jar)
* 对受影响的模块关闭混淆压缩,保证发出去的 apk 对 dex2jar 友好

## 提需求

想请求新源或报 bug,去上游仓库[提 issue](https://github.com/keiyoushi/extensions-source/issues/new/choose);如果是跟这个 fork 的构建/发布方式有关的问题,可以在本仓库开 issue。

## 贡献

本仓库只维护少量精选的源并自行构建发布;通用的贡献请提到上游的 [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source)。

## License

    Copyright 2015 Javier Tomás

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

## 免责声明

本项目与所收录内容的提供方没有任何关联。

本项目与 Mihon/Tachiyomi 官方没有任何关联,请不要在 Mihon/Tachiyomi 官方支持渠道询问这些扩展的问题。代码贡献归功于原始贡献者以及 [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source)。

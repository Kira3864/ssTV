# Third-party components

- Qt 6.8.3, dynamically linked, unmodified official MSVC 2022 build.
  Source: https://download.qt.io/archive/qt/6.8/6.8.3/single/
  License texts are included in `licenses/`.
- mpv / libmpv Windows x86_64 build, 20261021, revision e471496c56.
  Build recipe and dependent-library sources: https://github.com/shinchiro/mpv-winbuild-cmake/tree/1f8d2e7
  mpv source: https://github.com/mpv-player/mpv/tree/e471496c56
  Distribution: https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/20261021
  Copyright and license texts are included in `licenses/`.
- Microsoft Visual C++ runtime: unmodified app-local redistributable files.
  https://learn.microsoft.com/cpp/windows/redistributing-visual-cpp-files

Qt and libmpv remain separate DLLs; compatible replacement DLLs can be supplied
alongside the executable. Application source is in the repository's `desktop/`
directory. Components retain their individual licenses; see their notices.

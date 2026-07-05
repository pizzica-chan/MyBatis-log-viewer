@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

where mvn >nul 2>&1
if errorlevel 1 (
    echo Maven が見つかりません。Maven 3.6 以上をインストールし、PATH に追加してください。
    pause
    exit /b 1
)

echo MyBatis Log Viewer のテストを実行しています...
echo.

pushd "%~dp0mlv-java"
mvn test
set "EXIT_CODE=%ERRORLEVEL%"
popd

echo.
if not "%EXIT_CODE%"=="0" (
    echo テストに失敗しました。
    pause
    exit /b %EXIT_CODE%
)

echo すべてのテストが成功しました。
pause
exit /b 0

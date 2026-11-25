@echo off
chcp 65001 >nul 2>&1
setlocal EnableExtensions EnableDelayedExpansion

rem Windows counterpart of ./ztx. Keeps the same commands and .ztx layout.
set "ROOT_DIR=%~dp0"
if "%ROOT_DIR:~-1%"=="\" set "ROOT_DIR=%ROOT_DIR:~0,-1%"
set "STATE_DIR=%ROOT_DIR%\.ztx"
set "PID_DIR=%STATE_DIR%\pids"
set "LOG_DIR=%STATE_DIR%\logs"

if defined ZTX_HTTP_HOST (set "HTTP_HOST=%ZTX_HTTP_HOST%") else (set "HTTP_HOST=127.0.0.1")
if defined ZTX_HTTP_PORT (set "HTTP_PORT=%ZTX_HTTP_PORT%") else (set "HTTP_PORT=8080")
if defined ZTX_WEB_PORT (set "WEB_PORT=%ZTX_WEB_PORT%") else (set "WEB_PORT=5173")
set "HEALTH_URL=http://%HTTP_HOST%:%HTTP_PORT%/health/ready"
set "GRADLEW=%ROOT_DIR%\gradlew.bat"
set "SERVER_EXE=%ROOT_DIR%\backend\apps\server\build\install\server\bin\server.bat"

if "%~1"=="" goto :usage
if /i "%~1"=="help" goto :usage
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--help" goto :usage
if /i "%~1"=="dev" goto :cmd_dev
if /i "%~1"=="start" goto :cmd_start
if /i "%~1"=="stop" goto :cmd_stop
if /i "%~1"=="status" goto :cmd_status

echo 未知命令：%~1 >&2
call :usage
exit /b 2

:usage
echo ZTX 项目启动器（Windows）
echo.
echo 用法：
echo   ztx.bat dev      启动完整开发环境（Vite + Server + Worker + PostgreSQL）
echo   ztx.bat start    启动已打包 Web 资源的 Vert.x Server 和 Worker（不需要 Node.js）
echo   ztx.bat stop     停止本启动器启动的进程
echo   ztx.bat status   查看运行状态
echo   ztx.bat help     显示帮助
echo.
echo 访问地址：
echo   dev:   http://localhost:5173
echo   start: http://localhost:8080
echo.
echo 日志和 PID 文件保存在 .ztx\。Server 首次初始化口令也可能出现在 server.log，
echo 请勿共享日志文件。
exit /b 0

:require_command
where %~1 >nul 2>&1
if errorlevel 1 (
  echo 缺少命令：%~1 >&2
  exit /b 1
)
exit /b 0

:prepare_state
if not exist "%PID_DIR%" mkdir "%PID_DIR%" 2>nul
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%" 2>nul
exit /b 0

:check_java
call :require_command java || exit /b 1
if not exist "%GRADLEW%" (
  echo 缺少命令：%GRADLEW% >&2
  exit /b 1
)
exit /b 0

:check_database
call :require_command docker || exit /b 1
docker compose version >nul 2>&1
if errorlevel 1 (
  echo docker compose 不可用 >&2
  exit /b 1
)
echo 检查 PostgreSQL...
docker compose up -d --wait postgres
if errorlevel 1 exit /b 1
exit /b 0

rem current >= required returns 0, otherwise 1. Accepts v prefix / prerelease suffix.
:version_ge
set "VG_CUR=%~1"
set "VG_REQ=%~2"
powershell -NoProfile -Command "$c=('%VG_CUR%' -replace '^v',''); $r=('%VG_REQ%' -replace '^v',''); $c=$c -replace '-.*$',''; $r=$r -replace '-.*$',''; if ([version]$c -lt [version]$r) { exit 1 }"
exit /b %ERRORLEVEL%

:check_frontend_toolchain
call :require_command node || exit /b 1
call :require_command pnpm || exit /b 1
for /f "delims=" %%V in ('node --version') do set "NODE_VERSION=%%V"
for /f "delims=" %%V in ('pnpm --version') do set "PNPM_VERSION=%%V"
call :version_ge "%NODE_VERSION%" "v26.13.1"
if errorlevel 1 (
  echo 要求 Node.js 26.13.1 或更高版本，当前为 %NODE_VERSION% >&2
  exit /b 1
)
call :version_ge "%PNPM_VERSION%" "12.18.0"
if errorlevel 1 (
  echo 要求 pnpm 12.18.0 或更高版本，当前为 %PNPM_VERSION% >&2
  exit /b 1
)
exit /b 0

:is_running
set "CHK_NAME=%~1"
set "CHK_PID_FILE=%PID_DIR%\%CHK_NAME%.pid"
if not exist "%CHK_PID_FILE%" exit /b 1
set "CHK_PID="
set /p CHK_PID=<"%CHK_PID_FILE%"
if not defined CHK_PID exit /b 1
tasklist /FI "PID eq %CHK_PID%" /NH 2>nul | findstr /I /C:"%CHK_PID%" >nul
if errorlevel 1 exit /b 1
exit /b 0

:ensure_stopped
for %%N in (server worker web) do (
  call :is_running %%N
  if not errorlevel 1 (
    echo %%N 已在运行。请先执行 ztx.bat stop，再切换启动模式。 >&2
    exit /b 1
  )
)
exit /b 0

:sleep
set /a PING_COUNT=%~1+1
ping -n %PING_COUNT% 127.0.0.1 >nul 2>&1
exit /b 0

:tail_log
powershell -NoProfile -Command "Get-Content -Path '%~1' -Tail %~2 -ErrorAction SilentlyContinue"
exit /b 0

rem start_service <name> <exe> [args...]
:start_service
set "SV_NAME=%~1"
set "SV_LOG=%LOG_DIR%\%SV_NAME%.log"
set "SV_PID_FILE=%PID_DIR%\%SV_NAME%.pid"
call :is_running "%SV_NAME%"
if not errorlevel 1 (
  set /p RUN_PID=<"%SV_PID_FILE%"
  echo %SV_NAME% 已在运行（PID !RUN_PID!）
  exit /b 0
)
if exist "%SV_PID_FILE%" del /q "%SV_PID_FILE%" 2>nul
shift
set "SV_EXE=%~1"
shift
set "SV_ARGS="
:collect_args
if "%~1"=="" goto :collect_done
set "SV_ARGS=!SV_ARGS! + '%~1'"
shift
goto :collect_args
:collect_done
echo 启动 %SV_NAME%...
set "NEW_PID="
rem char34 avoids embedding double quotes in the powershell -Command string
for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$q=[char]34; $line=$q+'%SV_EXE%'+$q%SV_ARGS%+' 2>&1'; $p=Start-Process -FilePath 'cmd.exe' -ArgumentList ('/c '+$q+$line+$q) -RedirectStandardOutput '%SV_LOG%' -WindowStyle Hidden -PassThru; $p.Id"`) do set "NEW_PID=%%P"
if not defined NEW_PID (
  echo %SV_NAME% 启动失败，无法获取 PID >&2
  exit /b 1
)
echo(!NEW_PID!>"%SV_PID_FILE%"
call :sleep 1
call :is_running "%SV_NAME%"
if errorlevel 1 (
  if exist "%SV_PID_FILE%" del /q "%SV_PID_FILE%" 2>nul
  echo %SV_NAME% 启动失败，日志：%SV_LOG% >&2
  call :tail_log "%SV_LOG%" 60
  exit /b 1
)
echo %SV_NAME% 已启动（PID !NEW_PID!），日志：%SV_LOG%
exit /b 0

:wait_for_http
set "HTTP_DONE=0"
set "WH_URL=%~1"
set "WH_NAME=%~2"
set "WH_LOG=%~3"
for /L %%I in (1,1,60) do (
  if "!HTTP_DONE!"=="1" goto :http_done
  curl -s -f "%WH_URL%" >nul 2>&1
  if not errorlevel 1 (
    echo %WH_NAME% 健康检查通过：%WH_URL%
    set "HTTP_DONE=1"
  ) else (
    call :sleep 1
  )
)
:http_done
if "%HTTP_DONE%"=="1" exit /b 0
echo %WH_NAME% 未能在 60 秒内就绪，日志：%WH_LOG% >&2
call :tail_log "%WH_LOG%" 80
exit /b 1

:wait_for_worker
set "WORKER_DONE=0"
set "WW_LOG=%LOG_DIR%\worker.log"
for /L %%I in (1,1,30) do (
  if "!WORKER_DONE!"=="1" goto :worker_done
  findstr /C:"ZTX Worker 运行角色已启动" "%WW_LOG%" >nul 2>&1
  if not errorlevel 1 (
    echo Worker 启动检查通过
    set "WORKER_DONE=1"
  ) else (
    call :sleep 1
  )
)
:worker_done
if "%WORKER_DONE%"=="1" exit /b 0
echo Worker 未能在 30 秒内就绪，日志：%WW_LOG% >&2
call :tail_log "%WW_LOG%" 80
exit /b 1

:has_packaged_webroot
set "HAS_WEBROOT=0"
for %%J in ("%ROOT_DIR%\backend\apps\server\build\install\server\lib\*.jar") do (
  if exist "%%J" (
    jar tf "%%J" 2>nul | findstr /X /C:"webroot/index.html" >nul
    if not errorlevel 1 set "HAS_WEBROOT=1"
  )
)
if "%HAS_WEBROOT%"=="1" exit /b 0
exit /b 1

:start_worker
set "OLD_ROLE=%ZTX_APP_ROLE%"
set "OLD_MIGRATE=%ZTX_DB_MIGRATE_ON_START%"
set "ZTX_APP_ROLE=worker"
set "ZTX_DB_MIGRATE_ON_START=false"
call :start_service worker "%SERVER_EXE%" --ztx.profile=dev
set "WORKER_RC=%ERRORLEVEL%"
set "ZTX_APP_ROLE=%OLD_ROLE%"
set "ZTX_DB_MIGRATE_ON_START=%OLD_MIGRATE%"
if not "%WORKER_RC%"=="0" exit /b 1
call :wait_for_worker || exit /b 1
exit /b 0

:start_database_and_backend
call :check_database || exit /b 1
echo 构建后端运行发行包...
call "%GRADLEW%" --no-daemon :apps:server:installDist
if errorlevel 1 exit /b 1
if not exist "%SERVER_EXE%" (
  echo 未找到 Server 发布程序：%SERVER_EXE% >&2
  exit /b 1
)
call :start_service server "%SERVER_EXE%" --ztx.profile=dev || exit /b 1
call :wait_for_http "%HEALTH_URL%" "API Server" "%LOG_DIR%\server.log" || exit /b 1
call :start_worker || exit /b 1
exit /b 0

:cmd_dev
call :check_java || exit /b 1
call :check_frontend_toolchain || exit /b 1
call :prepare_state
call :ensure_stopped || exit /b 1
call :start_database_and_backend || exit /b 1
call pnpm --dir "%ROOT_DIR%\frontend" install --frozen-lockfile
if errorlevel 1 exit /b 1
call :start_service web node "%ROOT_DIR%\frontend\apps\erp-web\node_modules\vite\bin\vite.js" "%ROOT_DIR%\frontend\apps\erp-web" --host 0.0.0.0 || exit /b 1
call :wait_for_http "http://127.0.0.1:%WEB_PORT%/" Vite "%LOG_DIR%\web.log" || exit /b 1
echo.
echo ZTX 开发环境已启动：
echo   Web： http://localhost:%WEB_PORT%
echo   API： http://localhost:%HTTP_PORT%
echo   健康：%HEALTH_URL%
echo.
echo 日志目录：%LOG_DIR%
echo 停止环境：ztx.bat stop
exit /b 0

:cmd_start
call :check_java || exit /b 1
call :prepare_state
call :ensure_stopped || exit /b 1
call :check_database || exit /b 1
set "FRONTEND_DIST=%ROOT_DIR%\frontend\apps\erp-web\dist"
if defined ZTX_FRONTEND_DIST set "FRONTEND_DIST=%ZTX_FRONTEND_DIST%"
if exist "%FRONTEND_DIST%\index.html" (
  echo 将已构建的前端静态资源打包进 Vert.x Server...
  call "%GRADLEW%" --no-daemon :apps:server:installDist -Pztx.frontendDist="%FRONTEND_DIST%"
  if errorlevel 1 exit /b 1
) else if exist "%ROOT_DIR%\backend\apps\server\src\main\resources\webroot\index.html" (
  echo 使用 Server classpath 中已提供的 Web 静态资源...
  call "%GRADLEW%" --no-daemon :apps:server:installDist
  if errorlevel 1 exit /b 1
) else (
  if exist "%SERVER_EXE%" (
    call :has_packaged_webroot
    if not errorlevel 1 (
      echo 复用已包含前端静态资源的 Server 发行包...
      goto :start_servers
    )
  )
  echo 缺少预构建的前端静态资源或完整 Server 发行包。 >&2
  echo 请使用发布流水线生成包含 webroot/index.html 的发行包，或设置 ZTX_FRONTEND_DIST 指向已构建的 dist 目录。 >&2
  echo 开发环境请使用 ztx.bat dev（需要 Node.js 和 pnpm 以支持 Vite 热更新）。 >&2
  exit /b 1
)

:start_servers
if not exist "%SERVER_EXE%" (
  echo 未找到 Server 发布程序：%SERVER_EXE% >&2
  exit /b 1
)
call :start_service server "%SERVER_EXE%" --ztx.profile=dev || exit /b 1
call :wait_for_http "%HEALTH_URL%" "API Server/Web" "%LOG_DIR%\server.log" || exit /b 1
call :start_worker || exit /b 1
echo.
echo ZTX 已启动（由 Vert.x 提供页面和 API）：
echo   Web/API： http://localhost:%HTTP_PORT%
echo   健康检查：%HEALTH_URL%
echo.
echo 日志目录：%LOG_DIR%
echo 停止环境：ztx.bat stop
exit /b 0

:cmd_stop
set "STOP_FAILED=0"
for %%N in (web worker server) do call :stop_one %%N
if "%STOP_FAILED%"=="1" (
  echo 部分进程未能停止，请检查 %LOG_DIR% >&2
  exit /b 1
)
echo ZTX 应用进程已停止。PostgreSQL 数据库保持运行；如需停止数据库，请执行 docker compose stop postgres。
exit /b 0

:stop_one
set "SO_NAME=%~1"
set "SO_PID_FILE=%PID_DIR%\%SO_NAME%.pid"
if not exist "%SO_PID_FILE%" exit /b 0
set "SO_PID="
set /p SO_PID=<"%SO_PID_FILE%"
set "SO_ALIVE=0"
tasklist /FI "PID eq %SO_PID%" /NH 2>nul | findstr /I /C:"%SO_PID%" >nul
if not errorlevel 1 set "SO_ALIVE=1"
if "%SO_ALIVE%"=="1" (
  echo 停止 %SO_NAME%（PID %SO_PID%）...
  taskkill /PID %SO_PID% /T 2>nul
  set "SO_WAIT_DONE=0"
  for /L %%I in (1,1,10) do (
    if "!SO_WAIT_DONE!"=="0" (
      tasklist /FI "PID eq %SO_PID%" /NH 2>nul | findstr /I /C:"%SO_PID%" >nul
      if errorlevel 1 (
        set "SO_WAIT_DONE=1"
      ) else (
        call :sleep 1
      )
    )
  )
  tasklist /FI "PID eq %SO_PID%" /NH 2>nul | findstr /I /C:"%SO_PID%" >nul
  if not errorlevel 1 (
    echo %SO_NAME% 未正常退出，发送强制终止 >&2
    taskkill /PID %SO_PID% /T /F 2>nul
    if errorlevel 1 set "STOP_FAILED=1"
  )
)
del /q "%SO_PID_FILE%" 2>nul
exit /b 0

:cmd_status
for %%N in (server worker web) do (
  call :is_running %%N
  if not errorlevel 1 (
    set /p ST_PID=<"%PID_DIR%\%%N.pid"
    echo %%N：运行中（PID !ST_PID!）
  ) else (
    echo %%N：未运行
  )
)
where docker >nul 2>&1
if not errorlevel 1 docker compose ps postgres 2>nul
exit /b 0

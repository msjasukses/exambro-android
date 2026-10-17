@rem
@rem Gradle startup script for Windows, dibuat untuk project ExBro.
@rem Mengunduh gradle-wrapper.jar sekali bila belum tersedia.
@rem
@if "%DEBUG%"=="" @echo off
@setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.\
set APP_HOME=%DIRNAME%
set WRAPPER_DIR=%APP_HOME%gradle\wrapper
set WRAPPER_JAR=%WRAPPER_DIR%\gradle-wrapper.jar
set WRAPPER_JAR_URL=https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar

set DEFAULT_JVM_OPTS=

@rem ---------------------------------------------------------------------
@rem Cari java
@rem ---------------------------------------------------------------------
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
"%JAVA_EXE%" -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto checkWrapper
goto noJava

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto checkWrapper
echo.
echo ERROR: JAVA_HOME menunjuk ke direktori yang tidak valid: %JAVA_HOME%
echo.
goto fail

:noJava
echo.
echo ERROR: Java tidak ditemukan pada PATH maupun JAVA_HOME.
echo Pakai JDK bawaan Android Studio, contoh:
echo   set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
echo.
goto fail

@rem ---------------------------------------------------------------------
@rem Unduh gradle-wrapper.jar bila belum ada
@rem ---------------------------------------------------------------------
:checkWrapper
if exist "%WRAPPER_JAR%" goto execute

echo gradle-wrapper.jar belum ada, mengunduh dari repositori resmi Gradle...
if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%WRAPPER_JAR_URL%' -OutFile '%WRAPPER_JAR%'"
if exist "%WRAPPER_JAR%" goto execute
echo.
echo ERROR: gagal mengunduh gradle-wrapper.jar.
echo Unduh manual dari %WRAPPER_JAR_URL%
echo lalu simpan sebagai %WRAPPER_JAR%
echo.
goto fail

:execute
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%~n0" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*

if %ERRORLEVEL% equ 0 goto end

:fail
if not "%OS%"=="Windows_NT" exit 1
exit /b 1

:end
@endlocal

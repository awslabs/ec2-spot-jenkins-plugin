In progress

```bash
<powershell>
$temp = $env:SystemRoot + "\Temp\jenkins-slave"
mkdir $temp
cd $temp

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

(New-Object System.Net.WebClient).DownloadFile("https://download.java.net/openjdk/jdk8u40/ri/jdk_ri-8u40-b25-windows-i586-10_feb_2015.zip", $temp + "\jdk.zip")

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($temp + "\jdk.zip", $temp + "\jdk.exe")

# C:\Windows\Temp\jenkins-slave\jdk.exe\java-se-8u40-ri\bin

Invoke-WebRequest -OutFile jenkins-slave.exe https://github.com/kohsuke/winsw/releases/download/winsw-v2.2.0/WinSW.NET2.exe
Invoke-WebRequest -OutFile jenkins-slave.xml https://raw.githubusercontent.com/jenkinsci/ec2-fleet-plugin/master/test.xml
.\jenkins-slave.exe install
.\jenkins-slave.exe start
</powershell>
<persist>true</persist>
```
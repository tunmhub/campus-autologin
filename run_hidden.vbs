Option Explicit

Dim fso, shell, scriptDir, psScript, extraArgs, i, cmd
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
psScript = fso.BuildPath(scriptDir, "auto_login.ps1")

extraArgs = ""
If WScript.Arguments.Count > 0 Then
    For i = 0 To WScript.Arguments.Count - 1
        extraArgs = extraArgs & " " & WScript.Arguments(i)
    Next
End If

shell.CurrentDirectory = scriptDir
cmd = "powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File """ & psScript & """" & extraArgs
shell.Run cmd, 0, True

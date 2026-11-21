# 一叶便签 · 局域网接口 API

可选功能，默认关闭。开启后，同一局域网内的脚本或 AI 助手可以读取、追加、编辑和删除便签（包括按行编辑）。

> **风险提示**：明文 HTTP；令牌等同完整读写权限，拿到的人可以读取、修改、删除便签；只建议在可信局域网临时开启，用完关闭。`locked` 只约束界面，不拦接口写入。

## 开启与认证

托盘菜单「局域网接口」开启 →「复制接口信息」；地址形如 `http://<局域网IP>:17845`，所有请求带请求头 `X-Auth-Token`（令牌在 `%APPDATA%\DeskNote\api-token.txt`，重启不变）。

## 接口

| 方法 | 路径 | body |
| --- | --- | --- |
| GET | `/notes` | — |
| GET | `/notes/{id}` | — |
| POST | `/notes` | `{"text":"...","title":"..."}` |
| POST | `/notes/{id}/append` | `{"text":"..."}` |
| POST | `/notes/{id}/toggle-task` | `{"line":0}`（行号从 0 开始） |
| POST | `/notes/{id}/line/{n}` | `{"text":"..."}`（替换第 n 行，`text` 不能含换行） |
| POST | `/notes/{id}/title` | `{"title":"..."}`（空字符串即清空） |
| DELETE | `/notes/{id}/line/{n}` | — |
| DELETE | `/notes/{id}` | — |

`GET` 返回里的 `customTitle` 是显式标题；`POST /notes/{id}/title` 改的就是它，传空字符串则回退为正文首行显示。

状态码：`401` 未授权；`400` 参数不合法（缺字段、`text` 含换行、行号非法）；`404` 便签不存在、行号越界或目标行不是待办；错误返回 `{"error":"说明"}`。

```powershell
$base = "http://192.168.1.20:17845"
$h = @{ "X-Auth-Token" = (Get-Content "$env:APPDATA\DeskNote\api-token.txt" -Raw).Trim() }
Invoke-RestMethod "$base/notes" -Headers $h

# 替换第 1 行（行号从 0 开始）；把 /line/1 换成无 body 的 DELETE 即可删除该行
Invoke-RestMethod "$base/notes/{id}/line/1" -Method Post -Headers $h -ContentType "application/json" -Body '{"text":"新的第 1 行"}'
```

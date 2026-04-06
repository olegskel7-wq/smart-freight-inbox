# Smart Freight Inbox — публикация на GitHub (olegskel7-wq)
#
# Перед запуском:
# 1. Зайди на https://github.com/new
# 2. Owner: olegskel7-wq
# 3. Repository name: например smart-freight-inbox (должно совпадать с $RepoName ниже)
# 4. Без README / .gitignore / license (чтобы не было конфликта при первом push)
# 5. Сохрани — GitHub покажет URL репозитория

$RepoName = "smart-freight-inbox"   # <-- поменяй, если назвал репозиторий иначе
$GitHubUser = "olegskel7-wq"

$ProjectRoot = "C:\Users\olegs\Downloads\inbox\inbox"
Set-Location $ProjectRoot

# Инициализация репозитория (если ещё не делал)
if (-not (Test-Path .git)) {
    git init
}

git add .
git status

git commit -m "Initial commit: Smart Freight Inbox MVP"

git branch -M main

# HTTPS (попросит логин GitHub + Personal Access Token вместо пароля)
git remote remove origin 2>$null
git remote add origin "https://github.com/$GitHubUser/$RepoName.git"

git push -u origin main

Write-Host "`nГотово. Репозиторий: https://github.com/$GitHubUser/$RepoName" -ForegroundColor Green

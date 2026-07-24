from pathlib import Path

path = Path("TMessagesProj/src/main/AndroidManifest.xml")
text = path.read_text(encoding="utf-8")
for component in (
    'org.telegram.messenger.OpenChatReceiver',
    'org.telegram.messenger.OpenAttachedMenuBotReceiver',
):
    marker = f'android:name="{component}"'
    start = text.find(marker)
    if start < 0:
        raise RuntimeError(f'Missing {component}')
    end = text.find('</activity>', start)
    if end < 0:
        raise RuntimeError(f'Unterminated {component}')
    block = text[start:end]
    if block.count('android:exported="false"') != 1:
        raise RuntimeError(f'Expected one non-exported flag for {component}')
    block = block.replace('android:exported="false"', 'android:exported="true"', 1)
    text = text[:start] + block + text[end:]
path.write_text(text, encoding="utf-8")
print('Launcher shortcut activities preserved')

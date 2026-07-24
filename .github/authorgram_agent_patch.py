from pathlib import Path

path = Path("TMessagesProj/src/main/AndroidManifest.xml")
text = path.read_text(encoding="utf-8")


def one(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    text = text.replace(old, new, 1)

one('    <uses-permission android:name="android.permission.READ_CLIPBOARD"/>\n', '', 'obsolete clipboard permission')
one(
    '''        <activity
            android:name="org.telegram.messenger.OpenChatReceiver"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|uiMode"
            android:exported="true">
''',
    '''        <activity
            android:name="org.telegram.messenger.OpenChatReceiver"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|uiMode"
            android:exported="false">
''',
    'OpenChatReceiver exposure',
)
one(
    '''        <activity
            android:name="org.telegram.messenger.OpenAttachedMenuBotReceiver"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|uiMode"
            android:exported="true">
''',
    '''        <activity
            android:name="org.telegram.messenger.OpenAttachedMenuBotReceiver"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|uiMode"
            android:exported="false">
''',
    'OpenAttachedMenuBotReceiver exposure',
)
one(
    '''        <service
            android:name=".AuthenticatorService"
            android:exported="true"
            android:foregroundServiceType="dataSync"
''',
    '''        <service
            android:name=".AuthenticatorService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCOUNT_AUTHENTICATOR"
            android:foregroundServiceType="dataSync"
''',
    'AuthenticatorService permission',
)
one(
    '''        <service
            android:name=".ContactsSyncAdapterService"
            android:exported="true"
            android:foregroundServiceType="dataSync"
''',
    '''        <service
            android:name=".ContactsSyncAdapterService"
            android:exported="true"
            android:permission="android.permission.BIND_SYNC_ADAPTER"
            android:foregroundServiceType="dataSync"
''',
    'ContactsSyncAdapterService permission',
)
for service in (
    '.BringAppForegroundService',
    '.NotificationsService',
    '.VideoEncodingService',
    'org.telegram.ui.Stories.recorder.StoryUploadingService',
    '.ImportingService',
    '.LocationSharingService',
    '.MusicPlayerService',
):
    marker = f'''            android:name="{service}"'''
    start = text.find(marker)
    if start < 0:
        raise RuntimeError(f"Missing service {service}")
    end = text.find('/>', start)
    if end < 0:
        raise RuntimeError(f"Unterminated service {service}")
    block = text[start:end]
    if 'android:exported="true"' not in block:
        raise RuntimeError(f"Expected exported service {service}")
    block_new = block.replace('android:exported="true"', 'android:exported="false"', 1)
    text = text[:start] + block_new + text[end:]

one(
    '''        <service
            android:name=".MusicBrowserService"
            android:exported="true"
            android:foregroundServiceType="mediaPlayback"
''',
    '''        <service
            android:name=".MusicBrowserService"
            android:exported="true"
            android:permission="android.permission.BIND_MEDIA_BROWSER_SERVICE"
            android:foregroundServiceType="mediaPlayback"
''',
    'MusicBrowserService permission',
)

path.write_text(text, encoding="utf-8")
print("Android component exposure hardened")

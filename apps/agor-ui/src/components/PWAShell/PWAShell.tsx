import { CloudDownloadOutlined, DisconnectOutlined, DownloadOutlined } from '@ant-design/icons';
import { Alert, Button, Space, theme } from 'antd';
import { useEffect, useState } from 'react';
import { usePWAInstall, usePWAUpdate } from '../../hooks';

/**
 * Global PWA shell affordances:
 *  - "Install Agor" prompt when the browser fires `beforeinstallprompt`
 *  - "New version available" prompt when the service worker has an update waiting
 *  - "You're offline" banner when the browser reports `navigator.onLine === false`
 *
 * Each banner sits at the very top of the viewport and is dismissible. They do
 * NOT push the rest of the layout — they overlay so existing fixed headers
 * (board canvas, mobile prompt input) stay where they are.
 */
export function PWAShell() {
  const { token } = theme.useToken();
  const { canInstall, install, isInstalled } = usePWAInstall();
  const { updateReady, applyUpdate } = usePWAUpdate();
  const [installDismissed, setInstallDismissed] = useState(false);
  const [offline, setOffline] = useState(
    typeof navigator !== 'undefined' ? !navigator.onLine : false
  );

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const onOnline = () => setOffline(false);
    const onOffline = () => setOffline(true);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    return () => {
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
    };
  }, []);

  // Reset dismissal once installed so it would re-prompt next session if needed
  useEffect(() => {
    if (isInstalled) setInstallDismissed(true);
  }, [isInstalled]);

  const banners: React.ReactNode[] = [];

  if (offline) {
    banners.push(
      <Alert
        key="offline"
        banner
        type="warning"
        showIcon
        icon={<DisconnectOutlined />}
        message="You're offline. Reconnecting automatically when the network returns."
        style={{ borderRadius: 0 }}
      />
    );
  }

  if (updateReady) {
    banners.push(
      <Alert
        key="update"
        banner
        type="info"
        showIcon
        icon={<CloudDownloadOutlined />}
        style={{ borderRadius: 0 }}
        message={
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <span>A new version of Agor is ready.</span>
            <Button size="small" type="primary" onClick={applyUpdate}>
              Reload
            </Button>
          </Space>
        }
      />
    );
  }

  if (canInstall && !installDismissed) {
    banners.push(
      <Alert
        key="install"
        banner
        type="info"
        showIcon
        icon={<DownloadOutlined />}
        style={{
          borderRadius: 0,
          borderBottom: `1px solid ${token.colorBorderSecondary}`,
        }}
        message={
          <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
            <span>Install Agor for a full-screen app experience and faster relaunch.</span>
            <Space>
              <Button size="small" icon={<DownloadOutlined />} onClick={() => void install()}>
                Install
              </Button>
              <Button size="small" type="text" onClick={() => setInstallDismissed(true)}>
                Dismiss
              </Button>
            </Space>
          </Space>
        }
      />
    );
  }

  if (banners.length === 0) return null;

  return (
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        zIndex: 1100,
        pointerEvents: 'auto',
      }}
    >
      {banners}
    </div>
  );
}

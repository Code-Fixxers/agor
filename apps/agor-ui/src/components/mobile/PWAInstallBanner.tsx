import { DownloadOutlined } from '@ant-design/icons';
import { Alert, Button, Space, theme } from 'antd';
import { useState } from 'react';
import { usePWAInstall } from '../../hooks';

export const PWAInstallBanner: React.FC = () => {
  const { token } = theme.useToken();
  const { canInstall, install } = usePWAInstall();
  const [dismissed, setDismissed] = useState(false);

  if (!canInstall || dismissed) {
    return null;
  }

  return (
    <Alert
      banner
      type="info"
      style={{
        borderBottom: `1px solid ${token.colorBorderSecondary}`,
      }}
      message={
        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
          <span>Install Agor for a full-screen app experience and faster relaunch.</span>
          <Space>
            <Button size="small" icon={<DownloadOutlined />} onClick={() => void install()}>
              Install
            </Button>
            <Button size="small" type="text" onClick={() => setDismissed(true)}>
              Dismiss
            </Button>
          </Space>
        </Space>
      }
    />
  );
};

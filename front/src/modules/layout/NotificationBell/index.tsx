import { useEffect, useRef, useState } from 'react';
import { Bell, Robot, Warning, CheckCircle, ImageSquare } from '@phosphor-icons/react';
import {
  Wrapper,
  BellButton,
  Badge,
  Dropdown,
  DropdownHeader,
  MarkAllButton,
  NotificationList,
  NotificationItem,
  EmptyState,
} from './styles';
import { AppNotification } from '../../../hooks/useNotifications';

interface NotificationBellProps {
  notifications: AppNotification[];
  unreadCount: number;
  onMarkRead: (id: number) => void;
  onMarkAllRead: () => void;
  isMinimized?: boolean;
}

function formatRelativeTime(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const diffMin = Math.floor(diffMs / 60000);
  if (diffMin < 1) return 'agora';
  if (diffMin < 60) return `${diffMin}min atrás`;
  const diffHours = Math.floor(diffMin / 60);
  if (diffHours < 24) return `${diffHours}h atrás`;
  return `${Math.floor(diffHours / 24)}d atrás`;
}

function renderNotificationIcon(type?: string) {
  if (type === 'COWORK_TASK_EXECUTED' || type === 'COWORK_TASK_COMPLETED') {
    return <Robot size={18} color="var(--primary)" />;
  }
  if (type === 'COWORK_TASK_FAILED') {
    return <Warning size={18} color="#ef4444" />;
  }
  if (type === 'IMAGE_GENERATED') {
    return <ImageSquare size={18} color="var(--accent)" />;
  }
  return <CheckCircle size={18} color="var(--accent)" />;
}

export function NotificationBell({
  notifications,
  unreadCount,
  onMarkRead,
  onMarkAllRead,
  isMinimized,
}: NotificationBellProps) {
  const [isOpen, setIsOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isOpen) return;
    function handleClickOutside(event: MouseEvent) {
      if (wrapperRef.current && !wrapperRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isOpen]);

  return (
    <Wrapper ref={wrapperRef} $isMinimized={isMinimized} data-minimized={isMinimized ? 'true' : 'false'}>
      <BellButton onClick={() => setIsOpen(prev => !prev)} title="Notificações">
        <Bell size={18} weight={unreadCount > 0 ? 'fill' : 'regular'} />
        {unreadCount > 0 && <Badge>{unreadCount > 9 ? '9+' : unreadCount}</Badge>}
      </BellButton>

      {isOpen && (
        <Dropdown>
          <DropdownHeader>
            <span>Notificações</span>
            {unreadCount > 0 && <MarkAllButton onClick={onMarkAllRead}>Marcar todas como lidas</MarkAllButton>}
          </DropdownHeader>
          <NotificationList>
            {notifications.length === 0 && <EmptyState>Nenhuma notificação ainda.</EmptyState>}
            {notifications.map(notification => (
              <NotificationItem
                key={notification.id}
                $read={notification.read}
                onClick={() => !notification.read && onMarkRead(notification.id)}
              >
                <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                  <div style={{ marginTop: 2, flexShrink: 0 }}>
                    {renderNotificationIcon(notification.type)}
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="title">{notification.title}</div>
                    <div className="message">{notification.message}</div>
                    <div className="time">{formatRelativeTime(notification.createdAt)}</div>
                  </div>
                </div>
              </NotificationItem>
            ))}
          </NotificationList>
        </Dropdown>
      )}
    </Wrapper>
  );
}

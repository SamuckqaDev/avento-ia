import { useCallback, useEffect, useState } from 'react';
import { api } from '../services/apiClient';

export interface AppNotification {
  id: number;
  type: string;
  title: string;
  message: string;
  read: boolean;
  createdAt: string;
}

const API_NOTIFICATIONS = '/api/notifications';
const POLL_INTERVAL_MS = 20000;

export function useNotifications() {
  const [notifications, setNotifications] = useState<AppNotification[]>([]);

  const loadNotifications = useCallback(async () => {
    try {
      const { data } = await api.get<AppNotification[]>(API_NOTIFICATIONS);
      setNotifications(data);
    } catch (e) {
      console.error('Error loading notifications', e);
    }
  }, []);

  const markRead = useCallback(async (id: number) => {
    setNotifications(prev => prev.map(n => (n.id === id ? { ...n, read: true } : n)));
    try {
      await api.post(`${API_NOTIFICATIONS}/${id}/read`);
    } catch (e) {
      console.error('Error marking notification as read', e);
    }
  }, []);

  const markAllRead = useCallback(async () => {
    setNotifications(prev => prev.map(n => ({ ...n, read: true })));
    try {
      await api.post(`${API_NOTIFICATIONS}/read-all`);
    } catch (e) {
      console.error('Error marking all notifications as read', e);
    }
  }, []);

  useEffect(() => {
    loadNotifications();
    const interval = window.setInterval(loadNotifications, POLL_INTERVAL_MS);
    return () => window.clearInterval(interval);
  }, [loadNotifications]);

  const unreadCount = notifications.filter(n => !n.read).length;

  return { notifications, unreadCount, markRead, markAllRead };
}

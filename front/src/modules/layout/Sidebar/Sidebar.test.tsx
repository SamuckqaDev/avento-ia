import { render, screen } from '@testing-library/react';
import { ThemeProvider } from 'styled-components';
import { expect, it, vi } from 'vitest';
import { lightTheme } from '../../../styles/theme';

const authState = vi.hoisted(() => ({
  user: { displayName: 'Avento Dev', role: 'USER', hasAvatar: true },
}));

vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ user: authState.user }),
}));

vi.mock('../../../services/apiClient', () => ({
  api: { get: vi.fn() },
}));

import { SidebarComponent } from './index';

it('uses the avatar route served by the backend', () => {
  render(
    <ThemeProvider theme={lightTheme}>
      <SidebarComponent
        isMobileOpen={false}
        chats={[]}
        currentChatId={null}
        onNewChat={() => {}}
        onLoadChat={() => {}}
        onDeleteChat={async () => {}}
        onRenameChat={async () => {}}
        notifications={[]}
        unreadNotificationCount={0}
        onMarkNotificationRead={() => {}}
        onMarkAllNotificationsRead={() => {}}
        projectPaths={[]}
        removeProjectPath={() => {}}
        homeWorkspaceRoot={null}
        clearHomeWorkspaceRoot={() => {}}
        browseFolder={async () => null}
        authorizeHomeFolder={async () => null}
        loadProjectTree={() => {}}
        fileTree={[]}
        selectedFiles={new Set()}
        toggleFileSelection={() => {}}
        media={[]}
        onOpenMedia={() => {}}
        isDarkMode={false}
        toggleTheme={() => {}}
        isVoiceEnabled={false}
        handleToggleVoice={() => {}}
      />
    </ThemeProvider>,
  );

  expect(screen.getByAltText('Foto de perfil').getAttribute('src')).toBe('/api/auth/me/avatar');
});

export const PROFILE_AVATAR_ENDPOINT = '/api/auth/me/avatar';

const MAX_AVATAR_DIMENSION = 512;
const MAX_AVATAR_BYTES = 512 * 1024;

export function profileAvatarUrl(version?: number): string {
  return version ? `${PROFILE_AVATAR_ENDPOINT}?v=${version}` : PROFILE_AVATAR_ENDPOINT;
}

/** Reduces the image client-side before the server applies its matching 512 KiB limit. */
export async function resizeAvatarForUpload(file: File): Promise<File> {
  if (!file.type.startsWith('image/')) {
    return file;
  }

  const source = await loadImage(file);
  const scale = Math.min(1, MAX_AVATAR_DIMENSION / Math.max(source.width, source.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(source.width * scale));
  canvas.height = Math.max(1, Math.round(source.height * scale));
  canvas.getContext('2d')?.drawImage(source, 0, 0, canvas.width, canvas.height);

  for (const quality of [0.86, 0.72, 0.58]) {
    const blob = await canvasBlob(canvas, quality);
    if (blob.size <= MAX_AVATAR_BYTES) {
      return new File([blob], `${file.name.replace(/\.[^.]+$/, '') || 'avatar'}.jpg`, { type: 'image/jpeg' });
    }
  }

  throw new Error('Não foi possível reduzir a foto para o limite de 512 KiB.');
}

function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(url);
      resolve(image);
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('Não foi possível ler a imagem selecionada.'));
    };
    image.src = url;
  });
}

function canvasBlob(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(blob => {
      if (blob) {
        resolve(blob);
      } else {
        reject(new Error('Não foi possível reduzir a imagem selecionada.'));
      }
    }, 'image/jpeg', quality);
  });
}

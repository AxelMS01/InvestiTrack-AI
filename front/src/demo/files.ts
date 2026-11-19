import { seededFile } from './academic-seed';
const temporaryFiles = new Map<string, File>();

function openFiles() {
  return new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open('invesctictrack.demo.files', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('files');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function transact<T>(
  mode: IDBTransactionMode,
  run: (store: IDBObjectStore) => IDBRequest<T>,
) {
  const database = await openFiles();
  try {
    return await new Promise<T>((resolve, reject) => {
      const transaction = database.transaction('files', mode);
      const request = run(transaction.objectStore('files'));
      transaction.oncomplete = () => resolve(request.result);
      transaction.onabort = () => reject(transaction.error ?? new Error('Operación cancelada.'));
    });
  } finally {
    database.close();
  }
}

export const demoFiles = {
  async put(id: string, file: File) {
    if (typeof indexedDB === 'undefined') temporaryFiles.set(id, file);
    else await transact('readwrite', (store) => store.put(file, id));
  },
  async get(id: string): Promise<File | undefined> {
    const seed = seededFile(id);
    if (seed) return seed;
    if (typeof indexedDB === 'undefined') return temporaryFiles.get(id);
    const value: unknown = await transact('readonly', (store) => store.get(id));
    return value instanceof File ? value : undefined;
  },
  async remove(id: string) {
    if (typeof indexedDB === 'undefined') temporaryFiles.delete(id);
    else await transact('readwrite', (store) => store.delete(id));
  },
};

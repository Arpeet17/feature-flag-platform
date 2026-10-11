import type { EnvironmentWithSdkKey } from '../api/types';
import { CopyButton, EnvDot, Modal } from './ui';

/** SDK keys are only stored hashed server-side, so this is the one chance to copy them. */
export function SdkKeyReveal({ keys, onClose }: { keys: EnvironmentWithSdkKey[]; onClose: () => void }) {
  return (
    <Modal title="Save your SDK keys" onClose={onClose}
           footer={<button className="btn btn-primary" onClick={onClose}>I've saved them</button>}>
      <div className="banner banner-warning">
        These keys are shown <strong>only once</strong>. The platform stores just a hash, so they cannot be retrieved later
        (you can always rotate to get a new one).
      </div>
      <ul className="key-list">
        {keys.map(({ environment, sdkKey }) => (
          <li key={environment.key}>
            <span className="key-env"><EnvDot color={environment.color} /> {environment.name}</span>
            <code className="key-value">{sdkKey}</code>
            <CopyButton text={sdkKey} />
          </li>
        ))}
      </ul>
    </Modal>
  );
}

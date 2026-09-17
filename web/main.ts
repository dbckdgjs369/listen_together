// web/main.ts — FROZEN [S0]
// ★엔트리 배선은 여기서 끝난다. A·B 는 이 파일을 열지 않는다.
import './shared/styles/tokens.css';
import './shared/styles/shell.css';
import { bootShell } from './shared/shell';
import { mount as mountHost } from './host/index';
import { mount as mountListener } from './listener/index';

bootShell({ mountHost, mountListener });

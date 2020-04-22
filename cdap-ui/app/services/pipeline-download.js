/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

 const downloadPipeline = (pipelineConfig, postExportCb) => {
  const blob = new Blob([JSON.stringify(pipelineConfig, null, 4)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const exportFileName = `${pipelineConfig.name ? pipelineConfig.name : 'noname'}-${
    pipelineConfig.artifact.name
  }`;

  const a = document.createElement('a');
  a.href = url;
  a.download = `${exportFileName}.json`;

  const clickHandler = (event) => {
    event.stopPropagation();
    setTimeout(() => {
      postExportCb()
    }, 300);
  };

  a.addEventListener('click', clickHandler, false);
  a.click();
};

export default downloadPipeline;

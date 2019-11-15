/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the 'License'); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
*/

const wp = require('@cypress/webpack-preprocessor');
const wpconfig = require('../../webpack.config.cdap');
const path = require('path');
module.exports = (on) => {
  wpconfig.module.rules = wpconfig.module.rules.filter(
    (rule) => rule.loader !== 'eslint-loader' && String(rule.test) !== String(/\.tsx?$/)
  );
  wpconfig.module.rules.push({
    test: /\.ts?$/,
    use: [
      {
        loader: 'ts-loader',
        options: {
          transpileOnly: true,
        },
      },
    ],
    exclude: [/node_modules/, /lib/],
    include: [path.join(__dirname, '..'), path.join(__dirname, '..', '..', 'app')],
  });
  const options = {
    webpackOptions: {
      resolve: wpconfig.resolve,
      module: wpconfig.module,
    },
  };
  on('file:preprocessor', wp(options));
};

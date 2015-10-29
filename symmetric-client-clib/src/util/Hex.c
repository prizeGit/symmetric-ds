/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
#include "util/Hex.h"

char * SymHex_encode(const unsigned char *data, int inSize) {
    char *result = (char *) malloc(inSize * 2);
    int i;
    for(i = 0; i < inSize; i++) {
        sprintf(result + (i * 2), "%02x", data[i]);
    }
    return result;
}

unsigned char * SymHex_decode(const char *data, int *outSize) {
    *outSize = strlen(data) / 2;
    unsigned char *result = malloc(*outSize);
    char *pos = (char *) data;
    int i;
    for(i = 0; i < *outSize; i++, pos += 2) {
        sscanf(pos, "%2hhx", &result[i]);
    }
    return result;
}

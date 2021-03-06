/*
 * Copyright (c) 2013, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package ca.psiphon.ploggy;

import java.io.File;
import java.io.IOException;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

/**
 * Helper for adding friends by exchanging email attachments.
 * 
 * On the sending side, this launches an email draft containing self's
 * public identity as a ".ploggy" attachment. On the receiving side,
 * ActivityAddFriend handles ".ploggy" attachment invocations.
 *
 * This work flow can compromise unlinkability:
 * - The sent email will expose the Ploggy link between the sender
 *   and recipient to all email servers along the delivery path.
 * - The email attachment must be located on public storage for the email
 *   client app to read. This exposes the identity to all other apps.
 * - Because email is not face-to-face, it may be less likely that users
 *   will perform a sound, out-of-bound fingerprint verification process.
 * 
 * There's a warning prompt before the action is taken.
 * TODO: consider disabling the option entirely if the user selects an "unlinkable" posture.
 */
public class SendIdentityByEmail {

    private static final String LOG_TAG = "Send Identity By Email";

    private static final String PUBLIC_STORAGE_DIRECTORY = "Ploggy";
    // TODO: per-persona filenames?
    private static final String EMAIL_ATTACHMENT_FILENAME = "identity.ploggy";

    public static void composeEmail(Context context) {
        final Context finalContext = context;
        new AlertDialog.Builder(finalContext)
            .setTitle(finalContext.getString(R.string.label_email_self_title))
            .setMessage(finalContext.getString(R.string.label_email_self_message))
            .setPositiveButton(finalContext.getString(R.string.label_email_self_positive),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                // The attachment must be created on external storage (and be publicly readable)
                                // for the email client app to read it.
                                // TODO: some other method? delete the attachment?

                                File directory = new File(Environment.getExternalStorageDirectory(), PUBLIC_STORAGE_DIRECTORY);
                                directory.mkdirs();
                                File attachmentFile = new File(directory, EMAIL_ATTACHMENT_FILENAME);
                                Utils.writeStringToFile(Json.toJson(Data.getInstance().getSelf().mPublicIdentity), attachmentFile);

                                Intent intent = new Intent(Intent.ACTION_SEND);
                                intent.setType("message/rfc822");
                                intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(attachmentFile));
                                finalContext.startActivity(intent);
                            } catch (IOException e) {
                                Log.addEntry(LOG_TAG, e.getMessage());
                                Log.addEntry(LOG_TAG, "failed to compose email with .ploggy attachment");
                            } catch (ActivityNotFoundException e) {
                                Log.addEntry(LOG_TAG, e.getMessage());
                                Log.addEntry(LOG_TAG, "failed to compose email with .ploggy attachment");
                            } catch (Utils.ApplicationError e) {
                                Log.addEntry(LOG_TAG, "failed to compose email with .ploggy attachment");
                            }
                        }
                    })
            .setNegativeButton(finalContext.getString(R.string.label_email_self_negative), null)
            .show();            
    }
}

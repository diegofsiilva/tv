package io.canais;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The IPTV list's server, user and password, typed once on the TV; with an account saved, straight to the channels. */
public class LoginActivity extends Activity {
    private EditText server, user, pass;
    private TextView status;
    private Button enter;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Xtream.saved(this) != null && !getIntent().getBooleanExtra("change", false)) {
            openChannels();
            return;
        }
        int pad = Ui.dp(this, 32);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, pad);
        form.setBackground(Ui.round(Ui.WHITE, Ui.dp(this, 22)));
        form.setElevation(Ui.dp(this, 8));

        form.addView(Ui.text(this, "Canais", 34, Ui.INK, Ui.BOLD));
        TextView hint = Ui.text(this, "Os mesmos dados de acesso da sua lista (os do XCIPTV).", 16, Ui.DIM, null);
        hint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 20));
        form.addView(hint);

        Xtream old = Xtream.saved(this);
        server = field("Servidor (ex.: http://exemplo.com:8080)", InputType.TYPE_TEXT_VARIATION_URI, old == null ? "" : old.server);
        user = field("Usuário", InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, old == null ? "" : old.user);
        pass = field("Senha", InputType.TYPE_TEXT_VARIATION_PASSWORD, old == null ? "" : old.pass);
        form.addView(server);
        form.addView(user);
        form.addView(pass);
        pass.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pass.setOnEditorActionListener((v, action, e) -> {
            enter.requestFocus();
            return false;
        });

        enter = new Button(this);
        enter.setText("Entrar");
        enter.setTextSize(20);
        enter.setOnClickListener(v -> login());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 16);
        form.addView(enter, lp);

        status = Ui.text(this, "", 16, Ui.DIM, null);
        status.setPadding(0, Ui.dp(this, 12), 0, 0);
        form.addView(status);

        LinearLayout root = new LinearLayout(this);
        root.setGravity(Gravity.CENTER);
        root.setBackground(Ui.background());
        root.addView(form, new LinearLayout.LayoutParams(Ui.dp(this, 560), -2));
        setContentView(root);
        server.requestFocus();
    }

    private EditText field(String hint, int variation, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(20);
        e.setTextColor(Ui.INK);
        e.setHintTextColor(Ui.FAINT);
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        return e;
    }

    private void login() {
        final Xtream x = new Xtream(server.getText().toString(), user.getText().toString(), pass.getText().toString());
        if (x.user.isEmpty() || x.pass.isEmpty() || x.server.length() < 10) {
            status.setText("Preencha o servidor, o usuário e a senha.");
            return;
        }
        enter.setEnabled(false);
        status.setText("Conectando…");
        Ui.IO.execute(() -> {
            String error;
            try {
                error = x.login() ? null : "Usuário ou senha não aceitos pelo servidor.";
            } catch (Exception e) {
                error = "Não consegui falar com o servidor. Confira o endereço (com a porta, se tiver).";
            }
            final String msg = error;
            Ui.MAIN.post(() -> {
                if (isFinishing()) return;
                enter.setEnabled(true);
                if (msg != null) {
                    status.setText(msg);
                    return;
                }
                x.save(this);
                openChannels();
            });
        });
    }

    private void openChannels() {
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }
}

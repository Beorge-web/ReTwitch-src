package tv.retwitch.mod;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.util.Base64;

/**
 * Logos of the settings' link buttons, none of which Twitch ships. The Boosty and Telegram marks are from
 * simple-icons (CC0), as absolute curves in a 24 box: each contour is a start point, then cubics.
 */
final class SettingsLogos {

    static final float[][] BOOSTY = {
            {2.66f, 14.34f, 2.66f, 14.34f, 6.8f, 0f, 6.8f, 0f, 6.8f, 0f, 13.16f, 0f, 13.16f, 0f, 13.16f, 0f, 11.88f, 4.44f, 11.88f, 4.44f, 11.88f, 4.44f, 11.84f, 4.52f, 11.84f, 4.52f, 11.84f, 4.52f, 8.46f, 16.25f, 8.46f, 16.25f, 8.46f, 16.25f, 11.61f, 16.25f, 11.61f, 16.25f, 10.29f, 19.54f, 9.26f, 22.12f, 8.53f, 23.99f, 2.71f, 23.92f, 1.09f, 19.76f, 2.51f, 14.83f},
            {8.55f, 24f, 8.55f, 24f, 16.22f, 12.96f, 16.22f, 12.96f, 16.22f, 12.96f, 12.97f, 12.96f, 12.97f, 12.96f, 12.97f, 12.96f, 15.8f, 5.89f, 15.8f, 5.89f, 20.66f, 6.4f, 22.94f, 10.22f, 21.59f, 14.84f, 20.16f, 19.81f, 14.34f, 24f, 8.68f, 24f, 8.68f, 24f, 8.55f, 24f, 8.55f, 24f, 8.55f, 24f, 8.55f, 24f, 8.55f, 24f},
    };

    static final float[][] TELEGRAM = {
            {16.91f, 7.22f, 17.01f, 7.22f, 17.23f, 7.25f, 17.37f, 7.36f, 17.37f, 7.36f, 17.54f, 7.69f, 17.54f, 7.69f, 17.56f, 7.78f, 17.58f, 8f, 17.56f, 8.16f, 17.38f, 10.06f, 16.6f, 14.66f, 16.2f, 16.79f, 16.03f, 17.69f, 15.7f, 17.99f, 15.38f, 18.02f, 14.69f, 18.08f, 14.16f, 17.56f, 13.48f, 17.12f, 12.43f, 16.42f, 11.83f, 15.99f, 10.8f, 15.32f, 9.62f, 14.54f, 10.39f, 14.11f, 11.06f, 13.41f, 11.24f, 13.22f, 14.31f, 10.43f, 14.37f, 10.18f, 14.38f, 10.14f, 14.38f, 10.03f, 14.31f, 9.96f, 14.24f, 9.9f, 14.14f, 9.92f, 14.06f, 9.94f, 13.96f, 9.96f, 12.27f, 11.08f, 9f, 13.29f, 8.52f, 13.62f, 8.09f, 13.78f, 7.7f, 13.77f, 7.27f, 13.76f, 6.45f, 13.52f, 5.84f, 13.33f, 5.08f, 13.08f, 4.49f, 12.95f, 4.54f, 12.54f, 4.57f, 12.32f, 4.86f, 12.1f, 5.43f, 11.87f, 8.93f, 10.35f, 11.26f, 9.34f, 12.43f, 8.86f, 15.76f, 7.47f, 16.45f, 7.23f, 16.91f, 7.22f, 16.91f, 7.22f, 16.91f, 7.22f, 16.91f, 7.22f},
    };

    /** The RTE site's own icon. */
    private static final String RTE_PNG = ""
            + "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAYAAADimHc4AAAAAW9yTlQBz6J3mgAAN2VJREFUeNrtvXl8nVd57/td6x33rC1tzZJtSbblKXHszC"
            + "OB4JCEkDKU0jaU0hZSego9tNwPLaef21t6OHxuKUMLKaeH9nK5pXCB3pwCISnQzLETiO3Y8RQPsWXJmsc9D++w1v3j3ZLtxIG0Zezx0md5S1t7"
            + "b73vMz+/53mW4eK6uC6ui+viurgurovr4rq4Lq6L6+K6uC6ui+viurgurp/IMi6S4IevWNbESRoIQxCGGiSgfzSfLS+S9xVIqZQMtw1HP4TRz8"
            + "IRYAMmCOciJX9sq703RWtXnGx7MiI4YElTAlZzm8a7QaYiDgxfuf5/TRN01Y1X0tHdwfTENLGEy8133ES2M4N0BbghVlZgZBVWmyBwmnduAja4"
            + "SZOuvnZed/sObt5xA2OV41QWPb7w51/lO0/9T7yGz6Wbh9HalOVquUsZ6pp4q3ONk7G3hobqDw9oCVTwCNZtXMv4xASEr+y6xc874a+//nqEEN"
            + "QaNfbu3osTc/jqwX/k7le9jfJ4le03Xmrny0sxK2ZZisDwVYNqxdP5mZL2S6EyDKFSyZjasnlT48mHd9fveOsO4fs1bGnj+ZrHvrtTB15A38au"
            + "9MTY9HbbNm/PdWZf17W6o9NNOGJxfqG0NF846NXUN2wz/uD05PTcBz7wfh588EHe+c538ofv/S//azCg7jU4dvKYKNkVPX7sjLjhhmsShaVCjx"
            + "8E24PQ32rFRaubNGPSNmUQKB3WtVYNVfVrfj30wsB1YwdiTuKh97/vd6e/ev8/aC2kcExXD6zpF3ue298zOTHzFss2f2VwuG/TmqHeVGt7BteN"
            + "UVyqcvzgaXXyyNh4uVD98rr1Q//j4PePjL7m9a/Wjz74GDVdJiaSL3v95s99hOLEIg0oNCjOF3Tb2vbs0IaBy0MVbDVNsc2Ju1uSseTqZNqJt7"
            + "anRSqd0pZjoZTSXs3T5XxFL84VKCyVpiuNwnUfvfejX928ZeMzExOTtYGBIXn89Mjq+aWl9zgp423brtzYu2qo14glYlopwejIFGeOzzA3uShl"
            + "YPa3JDLvnp2e6e0e6vi7dWuHnwHq73/v+5EGqPA/GgNisKXWxcPPPIS2gUVMs9XcLFzxpkx76q5E2u1vySZSHR2tdmu2RaSSMRzLIggVSmm0Ei"
            + "A0Ak0QBOQXSwOnRyZ+fWpyauvxU0e/kE5kv9HX07Pw+KmRW/zQu3t445qe9ZuHtBboyfF5Dj13gtJ8RfS29XPHLXcyPLhOSCHajp069rbJqemB"
            + "kVOn/0LafGfyzGxDhfCqV72axx9/9OfXBGXbWla+LxVKhKECC7SvBS69Vsq4ob0v+/bWrpbr+wa6W9ra05i2wLZNbWhBeanE4uySGDk5TinvYT"
            + "sxlA5oaUvR1p6hra2V1tZ2Jicm9dEjJ8fnpwtfsaX9ZLVR/+VkNvHWG159pdHanuH486fEs88cprOth7e8/k284bY7xcahDcScGFprCsUizz9/"
            + "VO/a9fTuZ77/vY8+/tiT373mmqtr3/72d7j99tfzz//8wM8+A4aHh1e+D1WA4zqMj48jhEAgKHhFVFkL0AmZYUu6M/YrHX1ttw2uXzXQ0dtqCU"
            + "OB1BjC1I1KIEZPTHDy6CkSbpz+7gFiTgbTtKlUyhRKixTKC1QbFXIdrbR3dOjQl5w+caYwNzU7IS2yl155SeeWyzaJsdEz4ntP7mXr5u287z3v"
            + "44arriHuxrENV5iyaUy00GEYipmZWf3ccweefPbZZ//PI0eOPLxmYI33iY9/ikajFhFeiJ8PE6QNged5pNJp5qdmqeOBh4nJmljaeX33YNsv9K"
            + "7PXdG1OpdKZFy08HQQ+sLCpjhfFof2nmR+vMyN172Wu+58A+uH1pOMp5HCpNGos7i0wFJhiaXCPIeOPscjjz0qFheKSC1b/Lpq6cpl1epVvVRK"
            + "ZbH/mQNcf+UN4g//4A+5ZMMlHH3+eX3o0CE2rt/EpZdcSiKWQAuEYZi6t7dXZDKZq7p7u9/T09vjf/beex/zvHrwrW/dT2dnF1qHCGH8bDFgeG"
            + "OUxGg0hjLAgJhts7S0ICZmJvV7/9P7xb1/85etsk3e2tqeumtwePWr+oc6uuwUwoxDQxXQSgvHjFMv+Bzdd5o2t4f3/uGv8dpX7WDN6tWYpoVW"
            + "GoFASoHSGqU1WiuW8kvcdctBPv/5v+Pb//xtgqqiq71bJuw4Tz31NJuHNvK/f+BDXLZlGyeOj/Cxj35cPPzII1y29TLe+c53cvvtt9OR60QLLb"
            + "SGRCLpblg/vMM2zcSZu97Q2PvsvqfuvPMNIUA+v/izlYhJU9Da2oqUEoWm6lWZnJsStVpdzIpZbQvH+v5zT1+S6oi/u3991z0btg1c37euo8VO"
            + "G0JbAb5ugAgwTYugKhk9PMUVG67jQ3/wx9z2mlvpau/EEJIzo2d47JFHGRsdozWbJR6LIzQYQpKIxRlcPcAV2y/HMS2OHz1GpVFlZmYWR8b4o/"
            + "d/iBuuuhHLsHly5y4+/ZnPUKlUGB8fZ//+/dRqNYaHN5BKptBao7XWlmWbqVSqz7btTCzmHrrzzjfM33nn7QwOruPDH/7wzw4DhBR09nYyMzND"
            + "tVYRM+OzBHWfeqVmolkTzzpv6R1qf8/Axp43r9+6elVLR8LADvHxCIVCiwDTkEhlM3FyjqTO8fv/6YNcedkVGFKChunpaf7iYx/jM5/+NI899h"
            + "i+57Fu3VpSycT5Dj/bwpYtm1FKsfOpp5mamuV1t9zGO+7+DRKxNGjJs/v3cf/992NZFvF4nKWlJfbv348KNZs2bSKVSqG1FgCWZZnZbEt/e3t7"
            + "0NHR/uyv/urba1deebn42Mf+/GeLAXMzc3ieJ6peTRMgSdGe6LV3tK/KvG9wY/dvDG7svqx7TWvcigtCfK20L5QRokWIFBrXdKguesyeLvD2X3"
            + "wXr7nhFmzTRAiB53vc9z/v41Of+ksWlxaZX1hg3/59uDGHS7duxXXds35Ha5KpJOvXr2dpMc9z+59j+7YruOU1O4i5cYQQHDx4kAcefBClFKZp"
            + "YJomlXKFo88/T0tLls2bN+M4TtPfCu26jhuPx7tKpdL49u2XH/vt375H792796fDgDf8wh1cculmTEsyOzMXRQNpA2VpVFWDoM1u51UdqzO/Nb"
            + "Cx5zeGNvXe2DOYyySyjlDC14Hy0QQoEQotNaAxMKEOp49MsKZ7A+/45d+iO9eFViClZHR0lM997nMcPnKEZDKJ6zqUSmVeeOEFtl62lbVDa1ci"
            + "ExEJLul0mtWrVnPs6DHm5xa46cabaGttRRqSo0ef5/7770dphWlIfD9AGhIVaubm5rjsssvo7+8/J9rU2jTNlO/7sVQqte/w4cNzGzZs+MmDqI"
            + "mUy9jYGJ/8+CeRUvC3X/8biINuD9FFHKtVXJ0bTPz+uq39f77lqsF3rd/avyXXn7YMFwLl6VAHaAK0CNFCIbRCIDCUSXmpzplTM1wyfBl93atQ"
            + "WqCRKAWnRkY4duxEZI4iuSQej3HmzDgPPfQQ9UZ9JTwUMgp30YLNmzbxa29/O416nWPPH0M0v2KxGIZhIIAgCBFCcPev3s0HPvABwjBk165dVC"
            + "oVtNYrPLBt21yzZs21N9xw3Y7du3fH/uzPPvLTQbGf23eIHbftIOd28O43vgd84sEMl2UG3feu397/ye2v2vCfL7127SVdA60xEdN4qkGgPJQI"
            + "hJZKIBFNSqEFSC2Q2qS4VKWzrZstG7cScxIgDJAG5Vqdg4ePMT07j2FZ+GFApVqh1qgTqJB9+/eTX8pDUwMQItpaA4Idt9xKX3cfjz7yCL7vA9"
            + "CazdLZ3o7f8PHqDVb1reLX3/EO7rnn3axbt44nn3ySYrG4olWRJZIik2nJ9Pb27bjnnnsG/+iPPqh/bGFof38/lmWxtLTE0tIShiFQNlTCOmaX"
            + "wfGuk8wen3WNNrHWTZq3tXe33tmzpn1L7+pca6LVFqH08ZVHqEJANW9ENMPUF+eSTdWfWWR49aVsWL8ZgYFGI4WkXK5w8uRJqtUqUoBhSIaGhq"
            + "jVaoyNjTG/sEChWKCru/uC99LZ2cnll1/O3r17mZiYYGhoiGw2S3d3N6dOnUJridaKer3OmTPjTExMIKWkUqmcw4BlLXCMzs7urZs2bdn2m7/5"
            + "m8d+bAzwfZ+pqQk6OzuxrBwLCwtoQ2OmIJgKDWr0NVpqt7b2J97S1Ze7au2GgZZse1oEVGmIOqFSqGXiS/SyeJ5V6fOX0ppaw6Ojs5tca/tZh4"
            + "pmfn6eU6dO4fkephRcccU1vPe972ViYoJPfOITaK1QoXqZTFCjgauvvpoDBw5w8uRJhoaGaGtrY82aNTz11FM4jsPk5CSf/OSnCEPFnj17uOKK"
            + "KyiVSuc59mUTl0ql2l3X3b5927Zv/dgYMDw8TD6fx/MDUS1XCZNaU8AIFLn4avv6lo7EW1vaE6/q6s91dvTkhBO38UVN+2GDUIVCawUoEBq0FP"
            + "oCsr9C/GZx1XJs3FgMw5ArNxuGIaOjo5w5c4YwVMTdONdffz0333wzp0+f5stf/jJhEJBMvgxkLCJrt2rVKorFIuPj4wDkcjm2b9/OP/3TP60Q"
            + "9zvf+U4E9GmNaZrYtr1C/OUtpMDzG9bp0VPrZudncv9uBrz21h1npV41OH78OFNj0zyx6wl0oKmLuqaGgUub22Nf1dKRuLOjt+XmrlVtazO5pH"
            + "STtlYipB6WUVqhUeI886IjsosfhFoJDQKkKWk06jQ8D5GI3hcEAWNjYywuLiKEwHVdent7MQyDI0eOMDc3x/DwMMlU8mU1gOb7tNYsLS1FUZtp"
            + "cdNNN3HVVVfz2GOPYRgGoLFtm6GhIe644w56e3sjVyVExAChqVTLPPXMLvHVr3918MSZFwZ/JBqglMJ1XG597Q6OPv9xbnvL7Tz86L8g1woau/"
            + "2MzIgrcr2ZO7rWtL2uqz+3NtuRdNykiRIhnqoRqgAtlBBNex6RrukB/xV4oTQk+WKefGGJ9mxkzz3P48yZM1QqFUzTQGtFPp9n586dfPGLX2R6"
            + "eoa77747ygMuYN70sufRkSRXq1XCMMQwBMPDw3zgA39ALBZjz5499Pb2smPHrVx77bVcfvnltLScRXAr9SonR0/y+M7HxDe+dR8e1bZLtq8f+n"
            + "cxoL2zg6NHj/Jbv/kuHn70IT796b8Slm3y7fv+WdsZK9Y47G9K9Dtv7lrTetea9V1Dbd2ZmJuwUUag60FZaK1BaCFQEAkxAnVBpFavPCVWnhFo"
            + "hJYIZFNQJc8dPMiJUy+wdvUwQhiUSiVGT5+mWqkQTyTwfZ/77ruPb37zm+x7dh9Da4e49dZbicVdIrN3vn8Xzb9XrVWp1+uMnB4lXyjS1prFdB"
            + "xufs1rWTM4xOjoGK1trawdWksymcCQBqHyKeQLHD95gseefpzHdz3CyNhJ1q4boLV7tX7h+Anv360BjUaDv/jEn4t6rabbUu3UdE3QIgZ0TL++"
            + "s7PtF1et7djeN9SRTGYtrYSvPaooFQqNPhv1rdzmD6uenh9RCA2GlggMPD8kmYhz8tQYB489y6uuv4mEk8HzPMrlMkIIpBAopTjw3AGUUqxa1c"
            + "/73vteLtu69QcjsmimZ6dYKi3xwhMn+fzff563ve1tdHR0Ii2TwXXrGVy3HtAoFbBQnGN84gzPHz3M7me+z4HDzzE2e4ZUNsVlV12qK+WqOPz8"
            + "86enJqaP/psYIJr5c7VSRkjJ3v9+SG98+yBz4VzOTVk3dHW0/VKuu+XVq9d2t2c74kLaoQrwha8aIlJpifhXdzaJC5h+QZRqaVAhybTL2k2reH"
            + "jXt7l6+3XcdNVrSCaT9Pb1Y9s2ge8Tc236+3sZGhri7rvv5hfuuot4LP6y0ZUQUKs3eGLnTs5MjaJEwKc+++c8tfcJrrjyagYGhoi5cTyvRrGU"
            + "Z3LyDCdHXmB8/DSzs9PUG1WybVku3boBy3Y5fWJEHDt2slAqVR4QUhw0/3UmJ0Iry5UKlXKNSlDD7tRsfOdgzO6S23M9rb/U2Ze7s723ZVVHT9"
            + "YwXa18VaehfaF0yDLxf8QVg2gLTb6wQGs2x/zkHJ//4v+gO9fL2jXr+aW3vRXTkJQKBVat6mfTxo1s376doaGhFXhanyMQL47d9x/Yzz/e9zXi"
            + "aZerbtjKxNQoew4/xe5D3yOZjGM7DloFBGGdRqMBQpBJJ+ke6CAej9GoeYwcn2B8bIrCUgmtOZ5Kph8pTBYKr5gBTuZsP55WWqSstC7Vi8Iv0d"
            + "M6aL2hf7D3HR2rcpe297TGTUcrLWs0Ql8qoVAooZErxBf630po8SJ90GjBSniXLxSIOQk2bF7Pvp17ePC7D/Bbv9bL1VdfydrBAQLPJ5tpIZ3K"
            + "YFpW5HRVlEO8nFycPPUC/9fn/5bnjzzPuq1r6Oxtp3MgxeCWVczOzpPPF/A8D9OS2E6cWKwNy3Sp13xmFxYonilRLtZYGquCDxKJ0DpbKhS7k7"
            + "2O84rAOOFCGGos26UeVPn1Tb/JrtM7DaODy/qHWt63ZfvQPUObVm3OtMdsLJ+Augi0J7RQIpInAxn9aaRuoil62cGJc76az4mXs0AiAsoUK+VJ"
            + "ROR8hTKYnZ4nDBUDq9eQXyizb+9hOjs6Wbd2PZ2t7bRms8TcONIwUEq/2NuexYKavuL4iWPc+zf38o9f/xrVoEapVgIrINeZRskGi4U5GqqONj"
            + "Va+oQ0qNTLFMtlAqVJpdO0ZFtRoWBxtoAQ0N7VRq6zLY2lzFCEe14ZA8xIAOuNOr4KOOwfcczW4Oa1G3s/tHnb2jd2rsrmtBsS4BNqT2hU06tK"
            + "hDYwhYWFgaEEUgkMLTC1gcTA0BJDCwwhQUQSfZ5paUq+0BLLsCkXqoydmsZ1E7hxF7RqGjaTSqnB7PQCPV299HR38sLxF3hq1zMRdpPLkkomo3"
            + "hdgJCg5QqSgZCgUFRrFc5MnOGxJx7h05+9l+88/gCXXD1E/3AHU4sLTI7PEgaKbLaFeCKO40jiCYdUOk42m6G9vZ2+vj5W9fXimDb5uSJT43Mg"
            + "Fas3dnLJVev00OZ+w3R1ulSvnPmBDBCWiTAEwozwejoAgRtLc9faDX0f2rJt3Y2pzoRbURWhhUITosUyaiBBG1iYGLWQ+lyR0vg81ek8tfkitf"
            + "kStbki1fkSXqmBIQxMy46SFqGX89ummYk+zzYcZifzHHr2NC3pVtrb2yKsSOjoOoXJqVOTmIbL4EAf2dYUY+NjPPbE4xw6cpilchFpCpAKaQoC"
            + "7VPxKhRrRcZnz7D/0F4efPgB/v4rX+AfvvYl9j3/LFuuGmTD5Wto6U7Q0p5hcb7E6ZOTlJZqtLbm6OnpJteaJZ1OkUxmSMbS+NWAF46e5rlnjz"
            + "ExMUVLW5Jt125g7SX9xLOmsOIa2zWNubmF8If6AK1DdCgwOzXBBE560H7TwLreD27YOrQ1ljZF2SuipUZquWImmtV00ILyYpHq6Bx6oYztKWxp"
            + "NFU+2gpBVQVUpvMk+3NkerKYNvhoIqbSxIMEWoJhWNSrismRBVav6sdwbBQNNCGZbII1a1Zx9OgILZkUa4f6ePXtV3Hk4An2HHmSfUeeYWhgiM"
            + "E1a+jv68O2bSrVKuV6hcXFOaamJ1lYmqdUKZNvFNlwxSCDW/sp6wJhqOgbbCeWdNmz8zAjJyaYm16ib1UnPavbiCVs6l6DxfklpqfnKFdr5Npb"
            + "uOTKy+juaUe6UA9r1MMGtrAwXWEl0uZG8fJZpdXssgjROQVLxONd1ps2bR340LpL1mxyMqaoBkVCESJFZNsj76oj3daCerHG5OFTpIs+axNt9K"
            + "eyJEwLqQQhoIQgQDBfLnJycY5pXadr6xCZvlY86eHjNaMTDUJiGjEaRc1zT7/A5Og8GzavZcNla3ASGi+oYZkWtbJiz1NHmJ1eYOOm1QwPryIZ"
            + "T1KvhczOLrC4sETgexiGQAiJUmolqZNSIk0Ty3VwEzHau3JgezTCGkqBZTjEXIfCfIWD3zvF6eemCOoBsRYbYQoCHZLIOLR1JxlY10tXdw7bsQ"
            + "hCHz/0CAhRKCxh67Auwv17D566oAmSsmkUAd0WwiyO2268aXBD34c2b1+32c1IqkFJKKlWQhrJMgMEQhiEjZDFMzMwW2J7x2o2t3WRM03SQpA2"
            + "DBKmQcKQJAyDjOOSsB0KxSLTCwVi6RRuwo342DRFGlChwrFcMqkMtWqdkRfGKRYqOK6N7RgYpiSVTNLVkUMRMjk5xejYGSrVMhiCZDpNrjNHe2"
            + "c7qUwKJ+Fiuw7SEDT8KkqGmDGDdFuK1vYMmIpGWItMliFQKiRUAYmUTUtrhjDUFBZLeI0AJ+awam0PWy4fYt2WftK5GKHwqfs1Qh1EZrRpIUxt"
            + "4dcCNTk2WXgJA3bs2EGtVqNYLETJSQVJmptXD/d8aOuVG7fHc6aohiWBoZrEiQLMFeujJVJLGoUahdEZBmItbM52klUCM/AQWoFWCBRCKQwUFh"
            + "B3LGKOw+LCIsVKhXg6g+26KKIYXazgYoJkIkF7eyumYTI1Mc/k+AyVaoWgERI0FDEnRndnJ22t7VjSwWv4nBmd4uTxM4ydmmD89CRnTk8wOjLN"
            + "9Mw89UaNdEuCjs4cufY2Eqk4WoaE2kOLSMiEWL4GFTWLORYtrWl832dpoUjfQAdX37SFlvYEvvDwVYMAPwqThYySRi0xhIXwYWl63luaWXhpIr"
            + "aMbbe1tYme7CoOntm3JtuR/o3BDf3bUx22roZFlAgEmnOCx8icQJTuG1riFWqYFZ/engxJKRC+B1rja6g26sRsG9e0IAyQQuFKWJOKo/t72DM9"
            + "yfTzI3RvHsTJOHhaoIgSuVAofN3ATZls2raavsEOzpyZZHJygakzS9iWQSKWIBGLY9sWUmh0AKoOlcU6WklMxyCRsejua6W9q4VsW5JYwgahUV"
            + "oR6HpUI2iirGcx76ZHUppAeCQzNtuuHabWqBGYPp70AIkXNkAqtJbNSM9ENAt5JkJ71Qbzkwv1Sr7+xEsYcOT5IwwNDZFOZjh6+ojtthjXrRpq"
            + "v6Grr8XyVQ2lwx8I3Ojlf4IQR5okTQtLg1YKpKBSa/DC6ASZRJo1vd24hgU6xEDjaOhLZ6gozXMzk8wcHSU32IebcQkMgTYiooSEUbXLFLR2xs"
            + "l2rGPthtUsLuRZXFiiXKpT9SrUfIUUGtMy6OjLMDi8ilg8TiwRw41bmA5IM0AJHy9ooLVGoZet79n0Q1+4ChHqADflsvXa9SwViniqAUo23x9l"
            + "PlIZCE/TKNfRSmAhKSzkRSVfW/Aq6vBLGHDq5KkVWsqkzPZ0Za7tXdPWZSXQde0L/YrSWI1hGJhSRs65aToQAimNKEucmyWRytCdyyBDBTpEaE"
            + "1MSAYzWfwg5PDkFFOlKrnV3SS7W5EJC08EKBESEhJoQagCDMPATZv0tbTTv7YbQoHvB4RB2CxBCgxDYhgGoQqaPac+nvJRgd+MtqJ8Qp+b4OkL"
            + "E3+ZKUqENMIybZ0xWjuS+IGPDsMoMdQaoUBVfYpTi+Rn8wSBgQoRlUqlLkL53Vw6+z3jByFfZkoOrF7XeXfvYOcQbiiCKCoRPwwsM7SEekhtLk"
            + "/OdGizHcymrzBNC6UlYxOL+GFItjWDaUU4fQQvKywhSMcTxE2XerFKYXYBr9bAECamNBBCglAIqUAY+GGIF3gEYUCoQjQhwtBIE6QtEQYEeNT9"
            + "Cn7YwFMegfZQBFFnxXI+LuR5eflydUJcYEdZnEaLEKWDKLNWYGqBFQpU2aMynad4ehpmy7TgIOuCxdl8UK0ETwlh/eWlV9165IIMcGOuCFoDnD"
            + "gbegY63tre39YVSh9Q6Jc1QGeflhjgKUozC2Qw6IgncGRTKoTEdBwq9QZzC0tI0yCeTGCYRuTstEJqjYkgGYuTjiVwNJQX8pQXCviVOtqPXiMA"
            + "Q1sYGJjCxERGFUwVEoZ1VOgRBD6B76FUGKUeksihSprOdZnIIvr9CiRyYUQkCghE1NWpDQQSqQT4AlFXhMU69Zk8lYlF/OkC6ToMZ9sZbOvGxN"
            + "YL+dKpSiO4Vzvuw/OLheCCiVgikcRdV8cuJhKWY6S0DKOSmhS8kgFZLQBDEhqSmgoJ5HKxSaN1iGtbDA30UW/UGB2bQBoGq/s6cUw7kjrlY2if"
            + "mNZ0J2JkY710pjJMF/LMTBapzBYgZSHTNmYiRSyRxE64SNsEU4IhoJnwaSDUzUhKgNLNuF+fLbZorZuvlOdVImky5VzxElFsHDllH3Sg8GoNvG"
            + "KNoFjBrIeY9ZAsBh3JNnKxGBnbxbQsvaR0aNXD5/1C7RnP7K4lw5q4IAOu2HY56Xed4qn/thQjkHbohygrqmpKeb5knDWT+hyMUiEdEysVp1Dw"
            + "qIQhadNChhqhNabwaE27DK9bwwsjZxgdm6DheXS0pWnLJLAMiQwDLK2QoY8pTeKpFN2xGKV6loVGgwWvxuJ0lbKaoWwvIGI2ImYjXRszYWO5Dq"
            + "ZlgiGRpoG0JEIJ1DIYeG6by7JRPweG1hqUBqVDwjBEKBAKtB8SNgLqpSp+uY5o+EhPYYWalJa0uwlymRgZ0yRmWZhSgJQUKjWxMJcvutp84bLB"
            + "jfnJUKC1c+G+oK6uLv2FX/o2r/nidelNmzbEWvuTnJ4/KSpeAXluaHZegfDcnxWGZeCkE+TniizVarQlLWwEhg6RSuEYmo7WBNJYxcjoFBOT0+"
            + "Tzefq6u+hubyNmWUgaGAQI5QGCuDTIxBN0xuJUyVJSIfl6jUqjQdlvRI9BiapWaMNE2haGbWHFbQzHBANM2wIpQRoR6nkuICrOSrxSUeRWr9Wp"
            + "VmuEjQBLCYwmE4QX4GpIGzat8TQtboyEYZGSBglDYAqBH4ZoDIqe4Pj4TO301MJeO5596Krt1y5853t7seLu+fMBd911F1prvvHAl3GcL6b/+P"
            + "/40LbXv/H1CeJaP7b3YU5OHmapMoXGf4n11ytV22Y2bEjcZJylIGChWqU/mcGUBlKrZoTgYwtFLhPDHOpnZHSambl5nn9hjELFY1V3F5l4DFN6"
            + "oH0kGqlUtAHTkCRtm3bbJlAKD01Dh1Q9j1K9QbnmUW8EBJ4irNfwCfFCnwYaJQyUjKKy5UKYfrHd1xq0RoUa0w9xtCBuOSQdB9c0SWTSJG2HpG"
            + "kRkwaukFhCIKPaEIEGH8lS2eP45EJ4fHR671LF/3wu2bVrbFF56ViKYqN+lgEDAwOkUil6enq4++2/yP/3j1/ftHZg/e3Dqzda0pU67ibEvhMd"
            + "fP/QE8wWzqBFsNI2EhlUfY57iFownLiD25JiopinL53BdhMIFRlQoUNE6GMbmtakgz3YRyaZ5PTENCfHxpldLLCmr4eutjQJJ45FGNkAIxqm0C"
            + "oEL8Q2DBwhSAiBlgbKjaGcOEFS46MIBfgoGmGIFwSUa1UCrVFCNH1a04wqFTFDE2FbIoJkTMPElCa2YeKaFq5lYBoiEiZAaiBUoDR+qAgCja8U"
            + "NT9gvlRlslgLphaK31usND4emolHptI9JXXykBCG2Qx5gWf27OaXfvGtaK2JxWIcPXrUuueee/7ont9+13+57LKtjhBQ13Wmi+Ps2v+I+P7BJ1"
            + "mszKJWNEGjZBOW0HKlWC59SXkiz9yR06x2Emzr7o9CUj9yshI/wuG1SSgsvNBidqnC6elZZuaW0Bq623Os6monHrOxLYljmxiGRugA2WzeEsuE"
            + "WO5mWZaFCBMkBLSInHJ4ToarJUQt/GLlrWIlCxNNXKxZq2jKmWy24QRBSMOPtu8HNBo+jYZHtVaj4XvUvICxpSp10z3qJJMfDqXz9QnZUW81PG"
            + "EYdqR96Kg9vV6v88TjT3D9DdeLW177WkbHRtuuv+76P7n9tjtWxWMxgQaJRcxJikQ8gUZTLleoNSorYNn5tUaxgi7arkvDC5idnQOlSMUTuLYN"
            + "zTBSoJDN0NMQklgsRksqhevGqNXr5IsF5hYWWSiUmC+VyVdq1H2NrwReCKE00NIgCvolUkqENFayFSkERtM0GEThrSUFthQYETQQPQoDS0TSLr"
            + "Qg8AN836dR9yLIulyhVKpSLNdYyleYnJ5nen6R2flF5heXKBSLVGtVEIJUKkk8nWGhHKipxdLTntZ/O31yZiHVlham64KUaKHRUkcm6It//0U6"
            + "uzrF7OwsH/mv/1VcceWVmzZu3LjOdV25zHdDIGIixurcWqxLLVKxJLsPP8XU4hk8VV/BIPQyaIVAoZAxk+xAF57nc2x6CSUEw+2dtJk2rmpmwE"
            + "0mGAQ4KCzXJNGbpSsbZ6FYZXohT75UIV+poTRYtoPjuMRcm2TCxrGNyDRIgW2AYxrYhollmlimEYmD1s0QNCqiBUrjByFBqNBa4wcBQRgShCE1"
            + "L5Lker0JTyjV7Npr+ommxY3FHNoyCWIxG9uysG0Hx4phOC4NaXK64AVqtjA3PlGqpoGTp0e5641v1KdeOPXSQW3DNDl0+DCAUSoVr+vr60u6rq"
            + "uV0gihxbJqxowY/a2D2BsdLNNh7/PfY2zmFPWg0nQBYVPFIwenVICRNOkcXs2CZXN8co6GVmzp6qbdNHBCE0MrUJFfkCgkGkMI3IRJJtZCV2uG"
            + "xVKNQqVGodqgVK1Tr3tUKjXmF4PmwLXCFGCZAse0cG0Hx7KxTBPRJKJuElFpCLXCD1R0/o/WhKGPUmGk0SI6ksa2LGzLxjIMLEtimgaWaWCbJj"
            + "HHwbUtHMeI5gSkBEwUJg0tmc+XmVsqzTRCvhdPpUoj91wKH9+pt2/fjmU5ZxmwYdNGgiCgWCxSr9Z0a65t3fjExB2f+9znHK21vvzyy0U8Hjsv"
            + "y7KFQ3emH3PYxLViOM+7nJo8TtUroERUW9Wi2eohBMJQmBmT3LpeZiWcnJzDn1asy7TS5bgksXAkGE2NgDDqHdLgSLAdg7Sbod6apu4rKg2fmh"
            + "dQa/jU/Dqe18Dz6oRhuJLVBoHC9+toFXU+66aHFUJEeYE0kVJgWRG8YRkJTBNMM/IzrmPj2haWGb3OlCBl8/1SIJf9QtPHhAiUhkaomCvXOHxq"
            + "vDoxU/hu1eOR1Vdf2xCf+K5Y8/9069Pz0+dHkJu2bI7mcFMpkUqlkgcPHvxQqVR6v0S4w8PD4u1vfzu/8iu/Qnd3F6ZpNm9ErOAgJS/PyPRRnj"
            + "7wBIdHDpCvLhCIBrqJ5C9nj5FjNtE1WDozS3V6gUTNp8eO0Z/M0JlIETckho7qBKKZ8uvlIg8GIAmJNCtEEDbjeKU0KgwJVYhSTRAs1JF5CRVa"
            + "6RX/pAUI08AwJFIITGliGgaGFBgGCBlh/03DtVI9jQRKNSsgkYn1Q0HdD/BCTbURUmsEaqHoe2fmCnMj04tPL1S8vw7txFOpjlw4OzvL7//u+/"
            + "RHP/Gx8xhguLEYnueJ97znPTz++OPb8/n8e33fX62V1vPz82LPnj2MjY0hpaS9vYNEInF2lgqBbTqkE1lyre2YlhlNntRrhMvt5cs9nE3jadom"
            + "sWSCeCpBaMBipcxSqUzF82goCJCRUzXMZkUsIp4mJPptiBQhhgixRIjd3I6hiZmQsCVx2yBhmyRtg6RrkYpZpGM2yXhzxywSjkHcksQtgWuAbW"
            + "gMI0QKhZDNerSAQCgCwJfgSUElDMl7PguNBpOVEi/MzupTs4uVk5OLMyPTxeMnJ5eeHpsrfXOh5n/FF+Zzbd1dnpQGhUKBx3Y+8ZKk13BjLtVq"
            + "VRw6dMheWlp6a71e/4UwDO1mEUrUajWOHj3K008/zezsLIlEglwut9L7LoikKBXP0N7WievGKJVLVGtlgtCP2sZXGCAIdYiW4MQc3FQMI+7gG7"
            + "BYqzJbLLJQqVLxQwJpEEoJhgQpaEaL5zTwNgEZoYAwqrI1K1dReBo2TU70vCFU9L08+5xYrnZJhZagDAikwBPQ0FBRmplylZlKmalKmel6ndlG"
            + "g1m/wUS9RN4MlW5NBXXXPXpypvDV0dnKVxYqwTfLSjxpJJIjZjrpxWJxTMtmKb/YrD+/iAHpTEZYloVpmv35fP43fN/fKjRCay2X3xCGIfl8nn"
            + "379rF3715mZ2dJp9N0dXVFcbPWGMIgZifIteSIxRJUa1XK5SJBEKwA61FJoFlmlxphGrjJOG5LAiMdx3cMFhpVJkt5ZsplZotFFmt1yp6Ph6SO"
            + "xEMSSJPAkBGTDBNtWGCZYJogBUpEu5lNIQyJNiQYBqEUhEhCIQmkxBeSmhCUtaKoQ+YbdU4v5Xlhbo4T8/OM10qUHAM/EyNoiVFP2/hZF6urBb"
            + "c7i9Oe0YnOtkTP8MDB4Su3fv37O4+8kB5eX8umU7q8VNBCCtHwGgiBzrW3UyoWz2eA7dgik8kQhuG2SqXy9iAIOpoI4UvQ2DAMmZub4+mnn2bP"
            + "nj1UKhVyuRytrW1oHcXSjhWjJZ0l5saoNRpUK1WC0Edx1gkumyMtBEqCMiVm3MbJxHFaEui4jWcKFv0as+Uyc+UyM5UKE4UC0+UKC40GM/U68/"
            + "U61RBqSlAXgroW1LSmjsATggaSupDUEdSUpqI1BT9goeExV68zVa0yVa0wUa1wurDE6fwio6UCc0GDWtzCaG8hubqLRH8OmUui0g4qbULKRMdM"
            + "AssgNCVCOFZp0Wv7/uPPFdPJtom0cMqF/JLs6ummWqtqrTWWZRGGIZVy5aU937fe9jrzmWeeeVelUvmI7/sZoZEXYsBKx0Szhc+2bXbs2ME73v"
            + "EObr75ZlpaWpAGhNqn2Mhz5MwB9h15hhNnjrBYmiWgDjJcYcRKOtqENKSQGEqiQ40ONLVSlVq+TFjx8Kp1/LqH8AJoOlYDiEsbxzCxLTOqfBGh"
            + "0YaInKwKm3G8iqrKng7xQoWnFaEhwDIRlsSOO5iOgxW3sZMxrFgMw7UIZVQCDaK55BXfgJYIZSG1ydJYuX7gyVPewnjRi9uJbyYTyc9ec911B+"
            + "6//5vh9m3b9djYmLYti0qlwuz0zPkaAIie3t701NTUWzzPu15rLZsBgLhwo5Y+TyNOnDjB008/zejoKLZtk21pJR6L45gO2VR0Bk86lcUy7ZXs"
            + "MgzVuajXORGuQqFQErQpMGM2sZYkibYM8fYWEu0txHMZ4u1ZEh2tuG0ZPNegEZNUbUHV0dRtSdWJdtmWlG1B2RFUHUk9bkI2hWhPYXVGJiTe3U"
            + "qiu41EZ5ZYexqzJQ4Jk9AGz1AEIoiOQpARFqVl85pDSUymCIsm+584bky8MOcon4RXb2zyPG/T4uJiYfOWS0af2rnTz7W1iXK53Gzpf5EGLOaX"
            + "uPzyy9fPzc39ZbVavVUp1Zz7/OGFlygmjpqbXNdlYGCAm2++mTe96U1ccukW2tqyBCIgX11ifGGUkxNHOTZyiLGZEapeEU0AIjyvNZxzwjwQSC"
            + "3RuulnVsqEy0meRgVhVPBfMW/NBl59bm1rGegBw5QRcCRptjyeLSJpoVFCRa0o+mydQ6Cjz2v6Mh1oXJlm27pr2b/rOF/9wtepV7yorNoc3Xcd"
            + "91i2NftX/f39X/nwn364cPsdt9PZ1YXtupQKBSzbZnZyCmPt2nU8+M8PbqvX6r8aqrB9xTT9K1vIwzBkdnaWgwcPsnfvXsbHJ1BK09XeTTbdRm"
            + "u6nVxLB+2tHbhWjEa1gecHCC05a+3OJjf6HFR1+THCTyCUoKQGqREWSEdGtV9bIi0DYRkIW6JNgbYMsAykbSCtqCagpT5LcBkljGqF+MudeOcW"
            + "O5oXpAyUp7C0yzWbbuSWq+7gwO7neebpvZEQRK8TKI0Ow/YgCLZWypX6I48+cqinu9s7ffq0KBQKJBIJ5pqmyPjmN74h/vKTn7ylUq68SYfKFb"
            + "y8+XklGhGGITMzMxw4cICnn/4ehw4dxjJtOts66WztJpfuoDPbS1tLJ8lYBlM6oCSGtDEMC9uIIA5L2hiYiFAiogOwmtmxaDZ8mM35sMiPRF03"
            + "xnmlc3FOuWhlBEOcbUc/C6BGA91CNFvoz/liBVw1EaFFTGa4Ydst4o6b3kxrrItHvvs4e/c82yxrNtvum4mSClWmVqttr9dqjVWrVh84MzrmD6"
            + "5dK6Vh6JbWLE4shvmLb31rKvCDYRWGieVZh3/T+MQ5g8hCCOr1OidPnmRkZISHHnqIHTtu4S1veQvXXnstvdk+2hJtDPUMMZOfYq4wQ76Wp+ZX"
            + "8AOPwI92vVGnXq/j+Q28RoOwCZZVqlVqtTphEOD7AYHn4fs+QhoruYmUAmkIjGbWa5gSaUgs02oyQUVD2EqhlUJa5jmBwblT+SGm0DrwlM7PVc"
            + "StN94k7rjpTXSme5mdWqRYqDS5KM4rl0dlAgXQ0Wg0PnjixAnv0su2/t8z8/PV7q4uOTMzo6SUmAeeey7XaDQ2obUZZSZavty81L+GEed+Pzs7"
            + "y5e+9P/y+ONPcNttr+P222/nxpuup7e1j562HuqBT0018FQDL6jj+x5h4OF5HrV6jUajTq1W5dixYzzy6GNMTU2jtCIMAhr1GtVqjVq1RhCosw"
            + "Rs9gOZtoFlm00wzcSyrLNaoUE1meo4zktGk0A3R1IN4Xkh+dmiaJwxaZereOtbfplKqUZ+KR+dwKUvPMWvtdZBEHQUCvn/TRqydN11131l9969"
            + "/qWXXipWD6zRdPf0XOfY9nGJ0IaQoTjbjvQj20IILaXQlmVo17X12rVD+t33/Ja+//6v66npcR0Eng6Urz3V0L5uaF/XtK8ruqFKuhos6WqwqB"
            + "/Z+aB+9a3X60RrXBsxqZ20pd2MrZ201FYcLR00NhrrnG1HW7hoEUPLOFrGRbRj52yX838+Z4uY0CIePWKhDVPotUOD+tFHH9O7du3SW7du1UII"
            + "bYioFCXES3fUuSl0Mpk8PLxxw13v/c+/Z+a6O43BDeuFkUwkfrVcKt+K1s4PaIf594/S6bN+YnFxkcOHj7Bnz7OcOH4Sx3HItmRJJVLNRt+op1"
            + "JiYAmHRi3grz7xaf7pvm+iAk3oawJPE3oK7WsIBULJZudC1P0Q7ebPoUAEIvLegYAACAWoJpSpViDNC24diuhv6AhWKRaLdHf3kUwm+cevfY1K"
            + "cwz2ZYY5oxObtFZhGObqtXr32NjYc+nW7FSlXBaGYRh/1WjU+86eH6d/bEdZak2UAyCaUdM8hw8fYffuPYyOjhKPJ+jq6orw8nPaAqempvnTP/"
            + "0wCwuLzQP2lufFdLNPR0SRlBYX6NM429+z0ufZLFuuvOSH6fA5oaFu2vvevn6EEDz++ON4De8cV//iKcuzgqeUwg+CHq212dHe8eyJI88XDc/z"
            + "/rvW2jznb/1EzhJd1ogwDJifn2P//v088sjDFItFVq9eTSbT0nToBjMzM/zt3/5dNHB9fnR/TnyzDPixUos4S8NoilLIZTRPnBMRvQKf9iJmCC"
            + "FoNOocPXqUqempZrVMv4TgF1AEAFNpPYhgJpZJ7TeAD7/MKPpPdCmlKBQKPPvssxw+fJhcLsfAwABSSjzP47777mNhYeGC0nXuTWs0juPQ2dlJ"
            + "T08PnZ2d5HI5DMOgVqudF62dGyhEh228uMtbvyxT8vk8s7OzzUqbftnrucCxImitYn7gdz777LOHX1mv4U/y8L5mGLthwwb+5E/+hNe//vVIKf"
            + "njP/5j7r333hVId/mmtdYrGXlrayubN29m/fr1bN++feXQKCEEu3bt4oEHHsB1XeLxOLZtMzU1FQ1YLC0xMzMThblB8Iqv85VEiy9mgj7bCeM7"
            + "sdh3fmYZoLVm9erV/N7v/R7veMc7OH78OL/zO7/DoUOHzjuDx3EcXNdl+/btvPnNb+bGG2+ks7OTTCaDbduEYYjneRw8eJDdu3dzzTXX0NLSgm"
            + "ma7Ny5E9M0CcOQffv2cfToUfbt20c+n2+2ngSEYfgjuaeX8a6BAfwpP4NLCEE+n+fgwYNIKbnhhhtYtWoVY2NjFItFkskk11xzDVdddRXj4+O8"
            + "+c1v5p577qGvr49EIoHZPK6yVCrxzDPPMDMzQyKR4Prrr6ejo4N0Ok29XkdKyWte8xpuueUWrrnmGq644gquuOIKEokEk5OT1Gq1H80NSXEhWy"
            + "Z/Js+OPtdOT05O8qlPfQqtNffccw+XXHIJk5OTxONxTNPks5/9LEtLS4yOjkZNtEKcp/axWIwNGzZEB0lNTRGGUae3lJL+/n7+5V/+hXw+z5o1"
            + "a9i4cSODg4Ps27ePAwcO/Eik/8UHP73kJJAfddL149p9fX36C1/4gg7DUGut9cjIiP7d3/1dnUgktGEYetu2bfqhhx7SYRhqpZTWWmullA7DUI"
            + "dhqHfv3q3/4R/+QRcKhZXfVyoVfd999+nvf//7ulwu6127dukPfOADes2aNdp1XS2l/LHf18/Nf+AwOTnJvffey9LSEq7r8q1vfYuHH36YRqOB"
            + "lJJDhw7xxS9+kU2bNtHZ2Xmeo1ze52rYclw+MzPDo48+Sjab5Wtf+xojIyMrR1P+xDT+52VLKXUikdDJZHJFOiOYQ2rDMHQul9Of+cxndL1eP0"
            + "8DfN/XzzzzzIoGBEGgPc/T999/v7766qu167o6FotpwzC0lHIZPlh5/DHvnx8GXABjWXl+mXibN2/WDzzwwIopqtfremRkRD/00EP6S1/6ki4W"
            + "i7rRaOhHH31U33TTTS/7mT8xoeLnaJ1rTl5sUrTWKych/tmf/RkHDhxgufF4cnKSQqGwki/s3r2bj3zkI+zatetlP/Mntf7D/Ad8y50HyWSSgw"
            + "cP8uUvf5nl6tPmzZvp6upCa71SMLJtm4GBgXPg6Z/OMv+jMEAIwdVXX81dd91FrVYjkUjQaDRoaWkhk8kQj8dXws/bbruNV7/61ezZs4cPfvCD"
            + "7N+//yIDfhRY0tjYGLlcjje+8Y3RbMI5J9cuYzbLh7cuY0ONRuOnet3/of4P0FOnTvHXf/3XHD58mHg8vgKwvRgwWy6Z7tq1i7GxsYsM+FGZIM"
            + "Mw2LNnDx/96EcZGRlBCEGlUuHUqVMsLCwgpVzRAt/3OX369AoccZEBP6IIKQxDvvvd7/KFL3yBxcVFgiBgcXGRarV63usmJyeZn5//qV/3fygT"
            + "tIzz1Ot1vvSlL7Fz504SiQSbNm2io6NjRVN83+fo0aOMjIz81K/5/weZNMJX497GhgAAAABJRU5ErkJggg==";

    private SettingsLogos() {
    }

    /** {@code box}: the side of the square of the logo's coordinates to show. */
    static Drawable mark(float[][] contours, float left, float top, float box) {
        Path path = new Path();
        for (float[] c : contours) {
            path.moveTo(c[0], c[1]);
            for (int i = 2; i + 5 < c.length; i += 6) {
                path.cubicTo(c[i], c[i + 1], c[i + 2], c[i + 3], c[i + 4], c[i + 5]);
            }
            path.close();
        }
        path.offset(-left, -top);
        ShapeDrawable shape = new ShapeDrawable(new PathShape(path, box, box));
        shape.getPaint().setColor(Color.WHITE);
        shape.setIntrinsicWidth(96); // ImageView skips drawing a drawable without a size
        shape.setIntrinsicHeight(96);
        return shape;
    }

    private static Bitmap rte;

    /** Decoded once: the root page rebuilds on every keystroke of the search. */
    static Bitmap rte() {
        if (rte == null) {
            byte[] png = Base64.decode(RTE_PNG, Base64.DEFAULT);
            rte = BitmapFactory.decodeByteArray(png, 0, png.length);
        }
        return rte;
    }
}

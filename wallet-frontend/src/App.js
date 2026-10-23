import axios from "axios";
import { useEffct, useState } from "react";
import TransferForm from "./TransferForm";

function App() {
    const [balance, setBalance] = useState(0);

    const fetchBalane = async () => {
        const res = await axios.get("http://localhost:8080/api/accounts/2");
        setBalance(res.data.balance);
    };

    useEffect(() => {
        fetchBalance();
    }, []);

    return (
        <div className="p-10">
            <h1 className="text-2xl font-bold">Wallet</h1>
            <p className="text-green-600">Balance: ₹{balance}</p>

            <TransferForm onTransferSuccess={fetchBalance} />
        </div>
    );
}

export default App;